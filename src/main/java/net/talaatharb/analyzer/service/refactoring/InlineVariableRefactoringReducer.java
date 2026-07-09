package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inlines a local variable or field by replacing its usages with the initializer expression.
 *
 * <p>Required attributes:
 * <ul>
 *   <li>{@code variableName} — the name of the variable to inline.</li>
 * </ul>
 *
 * <p>Optional attributes:
 * <ul>
 *   <li>{@code replaceAllOccurrences} — {@code "true"} replaces every usage in the file and removes
 *       the variable declaration; {@code "false"} (default) replaces only the single usage at the
 *       hinted line number and leaves the declaration intact.</li>
 * </ul>
 *
 * <p>The action's line number is used as a hint to locate the variable declaration when
 * {@code replaceAllOccurrences} is {@code "false"} and also to find the specific usage to inline.
 */
public class InlineVariableRefactoringReducer implements ProjectRefactoringReducer {

    // Matches: [modifiers] TYPE variableName = EXPRESSION;
    private static final Pattern VAR_DECL = Pattern.compile(
            "^(\\s*)(?:(?:public|private|protected)\\s+)?(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\]?,\\s]+\\s+(\\w+)\\s*=\\s*(.+?)\\s*;\\s*$");

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.INLINE_VARIABLE == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        String variableName = action.getAttributes().get("variableName");
        if (variableName == null || variableName.isBlank()) {
            return RefactoringResult.noChange("No variableName provided in action attributes.");
        }

        boolean replaceAllOccurrences = Boolean.parseBoolean(
                action.getAttributes().getOrDefault("replaceAllOccurrences", "false"));

        List<String> lines = new ArrayList<>(Files.readAllLines(targetFile, StandardCharsets.UTF_8));

        // Find the variable declaration to extract the initializer expression
        int declarationIndex = findDeclarationIndex(lines, variableName, action.getLineNumber());
        if (declarationIndex < 0) {
            return RefactoringResult.noChange(
                    "Variable declaration for '" + variableName + "' not found in file.");
        }

        Matcher declMatcher = VAR_DECL.matcher(lines.get(declarationIndex));
        if (!declMatcher.matches()) {
            return RefactoringResult.noChange(
                    "Could not parse initializer for variable '" + variableName + "'.");
        }
        String initExpression = declMatcher.group(3).trim();

        Pattern usagePattern = Pattern.compile("(?<![\\w$])" + Pattern.quote(variableName) + "(?![\\w$])");
        int replacementCount = 0;

        if (replaceAllOccurrences) {
            for (int i = 0; i < lines.size(); i++) {
                if (i == declarationIndex) {
                    continue;
                }
                String original = lines.get(i);
                String replaced = usagePattern.matcher(original).replaceAll(Matcher.quoteReplacement(initExpression));
                if (!original.equals(replaced)) {
                    lines.set(i, replaced);
                    replacementCount++;
                }
            }
            // Remove the declaration after all usages have been replaced
            lines.remove(declarationIndex);
            // Collapse any blank line left behind
            if (declarationIndex < lines.size() && lines.get(declarationIndex).isBlank()
                    && declarationIndex > 0 && lines.get(declarationIndex - 1).isBlank()) {
                lines.remove(declarationIndex);
            }
        } else {
            // Inline only the single usage at the hinted line
            int hintedIndex = action.getLineNumber() - 1;
            if (hintedIndex >= 0 && hintedIndex < lines.size() && hintedIndex != declarationIndex) {
                String original = lines.get(hintedIndex);
                Matcher m = usagePattern.matcher(original);
                if (m.find()) {
                    lines.set(hintedIndex, m.replaceFirst(Matcher.quoteReplacement(initExpression)));
                    replacementCount = 1;
                }
            }
            if (replacementCount == 0) {
                // Fall back: replace the first usage anywhere in the file (excluding the declaration)
                for (int i = 0; i < lines.size(); i++) {
                    if (i == declarationIndex) {
                        continue;
                    }
                    String original = lines.get(i);
                    Matcher m = usagePattern.matcher(original);
                    if (m.find()) {
                        lines.set(i, m.replaceFirst(Matcher.quoteReplacement(initExpression)));
                        replacementCount = 1;
                        break;
                    }
                }
            }
        }

        if (replacementCount == 0) {
            return RefactoringResult.noChange(
                    "No usages of variable '" + variableName + "' found in file.");
        }

        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Inlined variable '" + variableName + "' with expression '" + initExpression + "'"
                        + " (" + replacementCount + " occurrence(s) replaced"
                        + (replaceAllOccurrences ? ", declaration removed" : "") + ").",
                Collections.singletonList(targetFile));
    }

    /**
     * Finds the zero-based index of the variable declaration line.
     * Uses the line-number hint first; falls back to scanning the whole file.
     */
    private int findDeclarationIndex(List<String> lines, String variableName, int hintLineNumber) {
        // Try the hinted line first (1-based hint → 0-based index)
        if (hintLineNumber > 0 && hintLineNumber <= lines.size()) {
            int idx = hintLineNumber - 1;
            Matcher m = VAR_DECL.matcher(lines.get(idx));
            if (m.matches() && variableName.equals(m.group(2))) {
                return idx;
            }
        }
        // Scan the whole file
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = VAR_DECL.matcher(lines.get(i));
            if (m.matches() && variableName.equals(m.group(2))) {
                return i;
            }
        }
        return -1;
    }
}
