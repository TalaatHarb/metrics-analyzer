package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class RemoveUnnecessaryImportRefactoringReducer implements ProjectRefactoringReducer {

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.REMOVE_UNNECESSARY_IMPORT == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        String importClass = action.getAttributes().get("importClass");
        if (importClass == null || importClass.isBlank()) {
            return RefactoringResult.noChange("No importClass provided in action attributes.");
        }

        String expectedStatement = "import " + importClass + ";";
        List<String> lines = Files.readAllLines(targetFile, StandardCharsets.UTF_8);

        int targetIndex = findImportLine(lines, expectedStatement, action.getLineNumber());
        if (targetIndex < 0) {
            return RefactoringResult.noChange(
                    "Import statement '" + expectedStatement + "' not found in file.");
        }

        List<String> updated = lines.stream()
                .filter(line -> lines.indexOf(line) != targetIndex || !line.trim().equals(expectedStatement))
                .collect(Collectors.toList());

        // Trim any consecutive blank lines that may have been left behind in the import block
        List<String> cleaned = collapseConsecutiveBlanks(updated);

        Files.write(targetFile, cleaned, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Removed unnecessary import '" + importClass + "'.",
                Collections.singletonList(targetFile));
    }

    private int findImportLine(List<String> lines, String expectedStatement, int hintLineNumber) {
        // Prefer the hinted line (1-based) for accuracy
        if (hintLineNumber > 0 && hintLineNumber <= lines.size()) {
            int idx = hintLineNumber - 1;
            if (lines.get(idx).trim().equals(expectedStatement)) {
                return idx;
            }
        }
        // Fall back to scanning the import block for the exact statement
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(expectedStatement)) {
                return i;
            }
        }
        return -1;
    }

    private List<String> collapseConsecutiveBlanks(List<String> lines) {
        List<String> result = new java.util.ArrayList<>();
        boolean prevBlank = false;
        for (String line : lines) {
            boolean isBlank = line.isBlank();
            if (isBlank && prevBlank) {
                continue;
            }
            result.add(line);
            prevBlank = isBlank;
        }
        return result;
    }
}
