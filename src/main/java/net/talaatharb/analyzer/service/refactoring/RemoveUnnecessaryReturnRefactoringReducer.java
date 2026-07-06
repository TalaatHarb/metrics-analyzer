package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class RemoveUnnecessaryReturnRefactoringReducer implements ProjectRefactoringReducer {

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.REMOVE_UNNECESSARY_RETURN == actionType;
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

        List<String> lines = Files.readAllLines(targetFile, StandardCharsets.UTF_8);
        int index = targetLine - 1;
        if (index < 0 || index >= lines.size()) {
            return RefactoringResult.noChange("Target line is out of range.");
        }

        String trimmed = lines.get(index).trim();
        if (!trimmed.equals("return;")) {
            // Try scanning nearby lines in case line number is slightly off
            index = findReturnStatement(lines, targetLine);
            if (index < 0) {
                return RefactoringResult.noChange(
                        "No bare 'return;' statement found near line " + targetLine + ".");
            }
        }

        lines.remove(index);
        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Removed unnecessary return statement.",
                Collections.singletonList(targetFile));
    }

    private int findReturnStatement(List<String> lines, int hintLine) {
        // Search within ±3 lines of the hint
        int start = Math.max(0, hintLine - 4);
        int end = Math.min(lines.size() - 1, hintLine + 2);
        for (int i = start; i <= end; i++) {
            if (lines.get(i).trim().equals("return;")) {
                return i;
            }
        }
        return -1;
    }
}
