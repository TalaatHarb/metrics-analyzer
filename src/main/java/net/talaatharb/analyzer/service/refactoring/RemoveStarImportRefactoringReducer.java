package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Removes a star import line (e.g. {@code import java.util.*;}).
 * The star-import package is provided in {@code attributes["importPackage"]}.
 * The line is matched by both the line-number hint and by content scan.
 * After removal, consecutive blank lines are collapsed to at most one.
 */
public class RemoveStarImportRefactoringReducer implements ProjectRefactoringReducer {

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.REMOVE_STAR_IMPORT == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        String importPackage = action.getAttributes().get("importPackage");
        if (importPackage == null || importPackage.isBlank()) {
            return RefactoringResult.noChange("No importPackage provided in action attributes.");
        }

        List<String> lines = Files.readAllLines(targetFile, StandardCharsets.UTF_8);
        String importStatement = "import " + importPackage.trim() + ".*;";

        int removeIndex = -1;
        int hint = action.getLineNumber() - 1;
        if (hint >= 0 && hint < lines.size() && lines.get(hint).trim().equals(importStatement)) {
            removeIndex = hint;
        }
        if (removeIndex < 0) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equals(importStatement)) {
                    removeIndex = i;
                    break;
                }
            }
        }

        if (removeIndex < 0) {
            return RefactoringResult.noChange("Star import '" + importStatement + "' not found in file.");
        }

        lines.remove(removeIndex);

        // Collapse consecutive blank lines
        boolean prevBlank = false;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).trim().isEmpty()) {
                if (prevBlank) {
                    lines.remove(i);
                } else {
                    prevBlank = true;
                }
            } else {
                prevBlank = false;
            }
        }

        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Removed star import '" + importStatement + "'.",
                Collections.singletonList(targetFile));
    }
}
