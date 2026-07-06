package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class RemoveUnnecessaryFinalModifierRefactoringReducer implements ProjectRefactoringReducer {

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.REMOVE_UNNECESSARY_FINAL_MODIFIER == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        String methodName = action.getAttributes().get("methodName");
        if (methodName == null || methodName.isBlank()) {
            return RefactoringResult.noChange("Missing methodName attribute.");
        }

        List<String> lines = Files.readAllLines(targetFile, StandardCharsets.UTF_8);
        int declarationIndex = findMethodDeclaration(lines, methodName, action.getLineNumber());
        if (declarationIndex < 0) {
            return RefactoringResult.noChange(
                    "Could not find private final method declaration for '" + methodName + "'.");
        }

        String declaration = lines.get(declarationIndex);
        if (!declaration.contains("final")) {
            return RefactoringResult.noChange("Method declaration does not contain 'final'.");
        }

        String updatedDeclaration = declaration.replaceFirst("\\bfinal\\b\\s*", "");
        if (updatedDeclaration.equals(declaration)) {
            return RefactoringResult.noChange("Failed to remove 'final' modifier from method declaration.");
        }

        lines.set(declarationIndex, updatedDeclaration);
        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Removed unnecessary final modifier from private method '" + methodName + "'.",
                Collections.singletonList(targetFile));
    }

    private int findMethodDeclaration(List<String> lines, String methodName, int hintedLineNumber) {
        Pattern methodPattern = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(");

        if (hintedLineNumber > 0 && hintedLineNumber <= lines.size()) {
            int hintedIndex = hintedLineNumber - 1;
            if (isPrivateFinalMethodDeclaration(lines.get(hintedIndex), methodPattern)) {
                return hintedIndex;
            }
            int nearby = findNearby(lines, hintedIndex, methodPattern);
            if (nearby >= 0) {
                return nearby;
            }
        }

        for (int i = 0; i < lines.size(); i++) {
            if (isPrivateFinalMethodDeclaration(lines.get(i), methodPattern)) {
                return i;
            }
        }
        return -1;
    }

    private int findNearby(List<String> lines, int center, Pattern methodPattern) {
        int start = Math.max(0, center - 3);
        int end = Math.min(lines.size() - 1, center + 3);
        for (int i = start; i <= end; i++) {
            if (isPrivateFinalMethodDeclaration(lines.get(i), methodPattern)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isPrivateFinalMethodDeclaration(String line, Pattern methodPattern) {
        return line != null
                && line.contains("private")
                && line.contains("final")
                && methodPattern.matcher(line).find();
    }
}
