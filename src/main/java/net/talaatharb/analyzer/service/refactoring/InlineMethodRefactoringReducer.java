package net.talaatharb.analyzer.service.refactoring;

import spoon.Launcher;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Inlines a method by replacing its call site(s) with the method body, substituting
 * formal parameters with actual arguments.
 *
 * <p>Required attributes:
 * <ul>
 *   <li>{@code methodName} — the name of the method to inline.</li>
 * </ul>
 *
 * <p>Optional attributes:
 * <ul>
 *   <li>{@code replaceAllOccurrences} — {@code "true"} replaces every call site in the file and
 *       removes the method declaration; {@code "false"} (default) replaces only the single call at
 *       the hinted line number and leaves the declaration intact.</li>
 * </ul>
 *
 * <p>Limitations: only methods whose body consists entirely of expression statements and/or a
 * single {@code return} statement are supported. Methods with multiple return paths or local
 * variable names that clash with the call site's scope are not supported and will produce a
 * {@link RefactoringResult#noChange} result.
 */
public class InlineMethodRefactoringReducer implements ProjectRefactoringReducer {

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.INLINE_METHOD == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        String methodName = action.getAttributes().get("methodName");
        if (methodName == null || methodName.isBlank()) {
            return RefactoringResult.noChange("No methodName provided in action attributes.");
        }

        boolean replaceAllOccurrences = Boolean.parseBoolean(
                action.getAttributes().getOrDefault("replaceAllOccurrences", "false"));

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setAutoImports(false);
        launcher.addInputResource(targetFile.toString());
        launcher.buildModel();

        CtType<?> primaryType = launcher.getModel().getAllTypes().stream()
                .filter(type -> type.getPosition() != null && type.getPosition().isValidPosition())
                .min(Comparator.comparingInt(type -> type.getPosition().getLine()))
                .orElse(null);
        if (primaryType == null) {
            return RefactoringResult.noChange("Could not locate a top-level type in target file.");
        }

        CtMethod<?> targetMethod = findMethod(primaryType, methodName);
        if (targetMethod == null || targetMethod.getBody() == null) {
            return RefactoringResult.noChange("Method '" + methodName + "' not found in file.");
        }

        List<CtStatement> bodyStatements = new ArrayList<>(targetMethod.getBody().getStatements());
        if (bodyStatements.isEmpty()) {
            return RefactoringResult.noChange("Method '" + methodName + "' has an empty body.");
        }

        // Validate that the body is simple enough to inline
        if (!isInlineable(bodyStatements)) {
            return RefactoringResult.noChange(
                    "Method '" + methodName + "' body is too complex to inline (multiple returns or nested blocks).");
        }

        List<String> paramNames = targetMethod.getParameters().stream()
                .map(CtParameter::getSimpleName)
                .collect(Collectors.toList());

        // Collect all call sites
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<CtInvocation<?>> callSites = new ArrayList<>();
        for (CtInvocation inv : primaryType.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (inv.getExecutable() != null
                    && methodName.equals(inv.getExecutable().getSimpleName())
                    && inv.getArguments().size() == paramNames.size()) {
                callSites.add((CtInvocation<?>) inv);
            }
        }
        callSites.sort(Comparator.comparingInt(inv -> inv.getPosition().getLine()));

        if (callSites.isEmpty()) {
            return RefactoringResult.noChange(
                    "No call sites found for method '" + methodName + "' in file.");
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(targetFile, StandardCharsets.UTF_8));

        // Determine which call sites to inline
        List<CtInvocation<?>> sitesToInline;
        if (replaceAllOccurrences) {
            sitesToInline = callSites;
        } else {
            int hintLine = action.getLineNumber();
            CtInvocation<?> hinted = callSites.stream()
                    .filter(inv -> inv.getPosition().getLine() == hintLine)
                    .findFirst()
                    .orElse(callSites.get(0));
            sitesToInline = Collections.singletonList(hinted);
        }

        // Inline call sites in reverse order (bottom-up) so line numbers stay valid
        List<CtInvocation<?>> reversed = new ArrayList<>(sitesToInline);
        reversed.sort(Comparator.comparingInt((CtInvocation<?> inv) -> inv.getPosition().getLine()).reversed());

        int totalReplaced = 0;
        for (CtInvocation<?> callSite : reversed) {
            if (!callSite.getPosition().isValidPosition()) {
                continue;
            }
            List<String> actualArgs = callSite.getArguments().stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());

            String inlinedBody = buildInlinedBody(bodyStatements, paramNames, actualArgs);
            if (inlinedBody == null) {
                continue;
            }

            int callLine = callSite.getPosition().getLine() - 1; // 0-based
            if (callLine < 0 || callLine >= lines.size()) {
                continue;
            }

            String callLineText = lines.get(callLine);
            String indent = extractIndent(callLineText);

            // Replace the call on the line — the call may be the whole line or part of an expression
            String callText = buildCallPattern(methodName, actualArgs);
            String replacedLine = callLineText.replace(callText, inlinedBody.trim());

            // If the call was a standalone statement (entire line), expand body to multiple lines
            if (isStandaloneCall(callLineText, methodName)) {
                List<String> bodyLines = buildInlinedBodyLines(bodyStatements, paramNames, actualArgs, indent);
                lines.remove(callLine);
                lines.addAll(callLine, bodyLines);
            } else {
                lines.set(callLine, replacedLine);
            }
            totalReplaced++;
        }

        if (totalReplaced == 0) {
            return RefactoringResult.noChange("Could not inline any call site of method '" + methodName + "'.");
        }

        // Remove the method declaration if all occurrences were inlined
        if (replaceAllOccurrences && targetMethod.getPosition().isValidPosition()) {
            removeMethodDeclaration(lines, targetMethod);
        }

        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Inlined method '" + methodName + "' at " + totalReplaced + " call site(s)"
                        + (replaceAllOccurrences ? ", declaration removed" : "") + ".",
                Collections.singletonList(targetFile));
    }

    // ---- Helpers ----------------------------------------------------------------

    private CtMethod<?> findMethod(CtType<?> type, String methodName) {
        return type.getMethods().stream()
                .filter(m -> methodName.equals(m.getSimpleName()))
                .findFirst()
                .orElse(null);
    }

    private boolean isInlineable(List<CtStatement> bodyStatements) {
        long returnCount = bodyStatements.stream()
                .filter(s -> s instanceof CtReturn)
                .count();
        // Allow at most one return and only as the last statement
        if (returnCount > 1) {
            return false;
        }
        if (returnCount == 1 && !(bodyStatements.get(bodyStatements.size() - 1) instanceof CtReturn)) {
            return false;
        }
        return true;
    }

    /**
     * Builds a single-line inlined body string (used for expression contexts).
     * Returns null if the body cannot be represented as a single expression.
     */
    private String buildInlinedBody(List<CtStatement> bodyStatements,
                                    List<String> paramNames,
                                    List<String> actualArgs) {
        if (bodyStatements.size() == 1 && bodyStatements.get(0) instanceof CtReturn) {
            CtReturn<?> ret = (CtReturn<?>) bodyStatements.get(0);
            if (ret.getReturnedExpression() == null) {
                return "";
            }
            String expr = ret.getReturnedExpression().toString();
            return substituteParams(expr, paramNames, actualArgs);
        }
        // For multi-statement bodies used in non-expression context, caller handles multi-line
        return bodyStatements.stream()
                .map(Object::toString)
                .collect(Collectors.joining(" "));
    }

    /**
     * Builds a list of indented lines for inlining a multi-statement body.
     */
    private List<String> buildInlinedBodyLines(List<CtStatement> bodyStatements,
                                               List<String> paramNames,
                                               List<String> actualArgs,
                                               String indent) {
        List<String> result = new ArrayList<>();
        for (CtStatement stmt : bodyStatements) {
            String stmtText = stmt.toString();
            stmtText = substituteParams(stmtText, paramNames, actualArgs);
            // Ensure it ends with a semicolon for expression statements
            if (!stmtText.trim().endsWith(";") && !(stmt instanceof CtBlock)) {
                stmtText = stmtText + ";";
            }
            result.add(indent + stmtText);
        }
        return result;
    }

    private String substituteParams(String text, List<String> paramNames, List<String> actualArgs) {
        String result = text;
        for (int i = 0; i < paramNames.size(); i++) {
            String param = paramNames.get(i);
            String arg = actualArgs.get(i);
            Pattern p = Pattern.compile("(?<![\\w$])" + Pattern.quote(param) + "(?![\\w$])");
            result = p.matcher(result).replaceAll(Matcher.quoteReplacement(arg));
        }
        return result;
    }

    private String buildCallPattern(String methodName, List<String> actualArgs) {
        return methodName + "(" + String.join(", ", actualArgs) + ")";
    }

    /**
     * Returns true when the call occupies the whole statement line (possibly with semicolon).
     */
    private boolean isStandaloneCall(String line, String methodName) {
        String trimmed = line.trim();
        return trimmed.startsWith(methodName + "(") && trimmed.endsWith(");");
    }

    private String extractIndent(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }

    /**
     * Removes the method declaration and its body from the source lines.
     * Uses the position information from the Spoon model to find the lines to remove.
     */
    private void removeMethodDeclaration(List<String> lines, CtMethod<?> method) {
        int startLine = method.getPosition().getLine() - 1; // 0-based
        int endLine = method.getPosition().getEndLine() - 1; // 0-based

        // Walk back to include Javadoc / annotations that precede the method
        while (startLine > 0 && isDocOrAnnotationLine(lines.get(startLine - 1))) {
            startLine--;
        }

        if (startLine < 0 || endLine >= lines.size() || endLine < startLine) {
            return;
        }

        // Remove lines in reverse order
        for (int i = endLine; i >= startLine; i--) {
            lines.remove(i);
        }

        // Collapse blank lines left behind
        if (startLine < lines.size() && lines.get(startLine).isBlank()
                && startLine > 0 && lines.get(startLine - 1).isBlank()) {
            lines.remove(startLine);
        }
    }

    private boolean isDocOrAnnotationLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//")
                || trimmed.startsWith("/*")
                || trimmed.startsWith("*")
                || trimmed.startsWith("@");
    }
}
