package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class TodoFixmeRefactoringReducer implements ProjectRefactoringReducer {
    private static final Pattern TODO_FIXME_PATTERN = Pattern.compile("\\b(TODO|FIXME)\\b");

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.NORMALIZE_TODO_FIXME_MARKER == actionType;
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

        String original = lines.get(index);
        String updated = TODO_FIXME_PATTERN.matcher(original).replaceAll("NOTE");
        if (original.equals(updated)) {
            return RefactoringResult.noChange("No TODO/FIXME marker found on target line.");
        }

        lines.set(index, updated);
        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified("TODO/FIXME marker normalized.", Collections.singletonList(targetFile));
    }
}
