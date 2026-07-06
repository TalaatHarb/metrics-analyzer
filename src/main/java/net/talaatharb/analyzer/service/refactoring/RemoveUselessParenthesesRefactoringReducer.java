package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class RemoveUselessParenthesesRefactoringReducer implements ProjectRefactoringReducer {

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.REMOVE_USELESS_PARENTHESES == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        int targetLine = action.getLineNumber();
        if (targetLine <= 0) {
            return RefactoringResult.noChange("Invalid target line.");
        }

        String expression = action.getAttributes().get("expression");
        if (expression == null || expression.isBlank()) {
            return RefactoringResult.noChange("No expression provided in action attributes.");
        }

        List<String> lines = Files.readAllLines(targetFile, StandardCharsets.UTF_8);
        int index = targetLine - 1;
        if (index < 0 || index >= lines.size()) {
            return RefactoringResult.noChange("Target line is out of range.");
        }

        String original = lines.get(index);
        String parenthesized = "(" + expression + ")";
        if (!original.contains(parenthesized)) {
            return RefactoringResult.noChange(
                    "Parenthesized expression '" + parenthesized + "' not found on target line.");
        }

        String updated = original.replace(parenthesized, expression);
        if (original.equals(updated)) {
            return RefactoringResult.noChange("No change produced.");
        }

        lines.set(index, updated);
        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Removed useless parentheses around '" + expression + "'.",
                Collections.singletonList(targetFile));
    }
}
