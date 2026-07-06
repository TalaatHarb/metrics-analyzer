package net.talaatharb.analyzer.service.refactoring;

import spoon.Launcher;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExtractMethodRefactoringReducer implements ProjectRefactoringReducer {
    private static final String DEFAULT_METHOD_NAME = "extractedMethod";

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.EXTRACT_METHOD == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        String methodName = normalizeMethodName(action.getAttributes().get("methodName"));
        if (!isValidJavaIdentifier(methodName)) {
            return RefactoringResult.noChange("Method name '" + methodName + "' is not a valid Java identifier.");
        }

        int startLine = parseInt(action.getAttributes().get("startLine"));
        int endLine = parseInt(action.getAttributes().get("endLine"));
        if (startLine <= 0 || endLine <= 0 || endLine < startLine) {
            return RefactoringResult.noChange("Invalid selection range for extract method.");
        }

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

        CtMethod<?> ownerMethod = findOwningMethod(primaryType, startLine, endLine);
        if (ownerMethod == null || ownerMethod.getBody() == null) {
            return RefactoringResult.noChange("Selection is not inside a method body.");
        }

        CtBlock<?> body = ownerMethod.getBody();
        List<CtStatement> selectedStatements = collectSelectedStatements(body, startLine, endLine);
        if (selectedStatements.isEmpty()) {
            return RefactoringResult.noChange("No extractable statements found in the selected range.");
        }

        if (!isContiguous(body.getStatements(), selectedStatements)) {
            return RefactoringResult.noChange("Selected lines must form a contiguous statement block.");
        }

        Set<String> declaredInsideSelection = collectDeclaredLocalNames(selectedStatements);
        Set<String> usedAfterSelection = collectUsedLocalNamesAfterSelection(body, selectedStatements);
        declaredInsideSelection.retainAll(usedAfterSelection);
        if (!declaredInsideSelection.isEmpty()) {
            return RefactoringResult.noChange(
                    "Selection declares local variable(s) used later: " + String.join(", ", declaredInsideSelection)
                            + ". Returning extracted values is not supported yet."
            );
        }

        Map<String, CtTypeReference<?>> requiredParams = collectRequiredParameters(selectedStatements);

        String uniqueMethodName = resolveUniqueMethodName(primaryType, methodName);

        CtMethod<Void> extracted = primaryType.getFactory().Core().createMethod();
        extracted.setSimpleName(uniqueMethodName);
        extracted.addModifier(ModifierKind.PRIVATE);
        extracted.setType(primaryType.getFactory().Type().VOID_PRIMITIVE);

        CtBlock<Void> extractedBody = primaryType.getFactory().Core().createBlock();
        for (CtStatement statement : selectedStatements) {
            extractedBody.addStatement(statement.clone());
        }
        extracted.setBody(extractedBody);

        List<String> orderedParamNames = new ArrayList<>(requiredParams.keySet());
        for (String paramName : orderedParamNames) {
            CtParameter<?> parameter = primaryType.getFactory().Core().createParameter();
            parameter.setSimpleName(paramName);
            parameter.setType(requiredParams.get(paramName).clone());
            extracted.addParameter(parameter);
        }
        CtStatement callStatement = primaryType.getFactory().Code().createCodeSnippetStatement(
                uniqueMethodName + "(" + String.join(", ", orderedParamNames) + ")"
        );

        List<CtStatement> bodyStatements = new ArrayList<>(body.getStatements());
        int insertAt = bodyStatements.indexOf(selectedStatements.get(0));
        if (insertAt < 0) {
            return RefactoringResult.noChange("Could not locate statement insertion point.");
        }

        for (CtStatement statement : selectedStatements) {
            body.removeStatement(statement);
        }
        body.addStatement(insertAt, callStatement);

        primaryType.addMethod(extracted);

        Path outputDir = Files.createTempDirectory("extract-method-refactor");
        launcher.setSourceOutputDirectory(outputDir.toFile());
        launcher.prettyprint();

        Path printedFile = findGeneratedFile(outputDir, targetFile.getFileName().toString());
        if (printedFile == null || !Files.isRegularFile(printedFile)) {
            return RefactoringResult.noChange("Failed to materialize transformed source file.");
        }

        Files.writeString(targetFile, Files.readString(printedFile, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Extracted " + selectedStatements.size() + " statement(s) into method '" + uniqueMethodName + "'.",
                Collections.singletonList(targetFile)
        );
    }

    private CtMethod<?> findOwningMethod(CtType<?> type, int startLine, int endLine) {
        return type.getElements(new TypeFilter<>(CtMethod.class)).stream()
                .filter(method -> method.getBody() != null)
                .filter(method -> method.getPosition() != null && method.getPosition().isValidPosition())
                .filter(method -> method.getBody().getPosition() != null && method.getBody().getPosition().isValidPosition())
                .filter(method -> method.getBody().getPosition().getLine() <= startLine
                        && method.getBody().getPosition().getEndLine() >= endLine)
                .min(Comparator.comparingInt(method -> method.getPosition().getLine()))
                .orElse(null);
    }

    private List<CtStatement> collectSelectedStatements(CtBlock<?> body, int startLine, int endLine) {
        List<CtStatement> selected = new ArrayList<>();
        for (CtStatement statement : body.getStatements()) {
            if (statement.getPosition() == null || !statement.getPosition().isValidPosition()) {
                continue;
            }
            int stmtStart = statement.getPosition().getLine();
            int stmtEnd = statement.getPosition().getEndLine();
            if (stmtStart >= startLine && stmtEnd <= endLine) {
                selected.add(statement);
            }
        }
        return selected;
    }

    private boolean isContiguous(List<CtStatement> allStatements, List<CtStatement> selectedStatements) {
        if (selectedStatements.isEmpty()) {
            return false;
        }
        int first = allStatements.indexOf(selectedStatements.get(0));
        int last = allStatements.indexOf(selectedStatements.get(selectedStatements.size() - 1));
        if (first < 0 || last < 0 || last < first) {
            return false;
        }
        if ((last - first + 1) != selectedStatements.size()) {
            return false;
        }
        for (int i = first; i <= last; i++) {
            if (!selectedStatements.contains(allStatements.get(i))) {
                return false;
            }
        }
        return true;
    }

    private Set<String> collectDeclaredLocalNames(List<CtStatement> selectedStatements) {
        Set<String> names = new LinkedHashSet<>();
        for (CtStatement statement : selectedStatements) {
            for (CtLocalVariable<?> local : statement.getElements(new TypeFilter<>(CtLocalVariable.class))) {
                if (local.getSimpleName() != null) {
                    names.add(local.getSimpleName());
                }
            }
        }
        return names;
    }

    private Set<String> collectUsedLocalNamesAfterSelection(CtBlock<?> body, List<CtStatement> selectedStatements) {
        Set<String> used = new LinkedHashSet<>();
        List<CtStatement> statements = body.getStatements();
        int lastIndex = statements.indexOf(selectedStatements.get(selectedStatements.size() - 1));
        if (lastIndex < 0 || lastIndex + 1 >= statements.size()) {
            return used;
        }
        for (int i = lastIndex + 1; i < statements.size(); i++) {
            CtStatement statement = statements.get(i);
            for (CtVariableAccess<?> access : statement.getElements(new TypeFilter<>(CtVariableAccess.class))) {
                if (access.getVariable() != null && access.getVariable().getSimpleName() != null) {
                    used.add(access.getVariable().getSimpleName());
                }
            }
        }
        return used;
    }

    private Map<String, CtTypeReference<?>> collectRequiredParameters(List<CtStatement> selectedStatements) {
        Map<String, CtTypeReference<?>> params = new LinkedHashMap<>();

        for (CtStatement statement : selectedStatements) {
            for (CtVariableAccess<?> access : statement.getElements(new TypeFilter<>(CtVariableAccess.class))) {
                if (access.getVariable() == null) {
                    continue;
                }
                if (!(access.getVariable() instanceof CtLocalVariableReference
                        || access.getVariable() instanceof CtParameterReference)) {
                    continue;
                }
                String variableName = access.getVariable().getSimpleName();
                CtElement declaration = access.getVariable().getDeclaration();
                boolean declaredInsideSelection = declaration != null && isElementInsideSelection(declaration, selectedStatements);
                if (declaredInsideSelection) {
                    continue;
                }
                if (variableName == null || variableName.isBlank() || params.containsKey(variableName)) {
                    continue;
                }
                CtTypeReference<?> typeRef = access.getType();
                if (typeRef == null) {
                    typeRef = access.getVariable().getType();
                }
                if (typeRef == null) {
                    continue;
                }
                params.put(variableName, typeRef);
            }
        }
        return params;
    }

    private boolean isElementInsideSelection(CtElement element, List<CtStatement> selectedStatements) {
        CtElement current = element;
        while (current != null) {
            for (CtStatement statement : selectedStatements) {
                if (statement == current) {
                    return true;
                }
            }
            current = current.getParent();
        }
        return false;
    }

    private Path findGeneratedFile(Path outputDir, String fileName) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.walk(outputDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> fileName.equals(path.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private String normalizeMethodName(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return DEFAULT_METHOD_NAME;
        }
        return methodName.trim();
    }

    private String resolveUniqueMethodName(CtType<?> type, String baseName) {
        Set<String> existing = new LinkedHashSet<>();
        for (CtMethod<?> method : type.getMethods()) {
            existing.add(method.getSimpleName());
        }
        if (!existing.contains(baseName)) {
            return baseName;
        }
        int suffix = 2;
        while (existing.contains(baseName + suffix)) {
            suffix++;
        }
        return baseName + suffix;
    }

    private boolean isValidJavaIdentifier(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(methodName.charAt(0))) {
            return false;
        }
        for (int i = 1; i < methodName.length(); i++) {
            if (!Character.isJavaIdentifierPart(methodName.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
