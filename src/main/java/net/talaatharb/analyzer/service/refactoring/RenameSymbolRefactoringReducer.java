package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class RenameSymbolRefactoringReducer implements ProjectRefactoringReducer {

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.RENAME_SYMBOL == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        String oldName = action.getAttributes().get("oldName");
        String newName = action.getAttributes().get("newName");

        if (oldName == null || oldName.isBlank()) {
            return RefactoringResult.noChange("No oldName provided in action attributes.");
        }
        if (newName == null || newName.isBlank()) {
            return RefactoringResult.noChange("No newName provided in action attributes.");
        }
        if (!isValidIdentifier(newName)) {
            return RefactoringResult.noChange("New name '" + newName + "' is not a valid Java identifier.");
        }
        if (oldName.equals(newName)) {
            return RefactoringResult.noChange("Old and new names are identical.");
        }

        // Word-boundary match on Java identifiers (letters, digits, _, $)
        Pattern pattern = Pattern.compile(
                "(?<![\\w$])" + Pattern.quote(oldName) + "(?![\\w$])");

        List<String> lines = Files.readAllLines(targetFile, StandardCharsets.UTF_8);
        int replacementCount = 0;
        for (int i = 0; i < lines.size(); i++) {
            String original = lines.get(i);
            String updated = pattern.matcher(original).replaceAll(newName);
            if (!original.equals(updated)) {
                lines.set(i, updated);
                replacementCount++;
            }
        }

        if (replacementCount == 0) {
            return RefactoringResult.noChange(
                    "Symbol '" + oldName + "' not found in file.");
        }

        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Renamed '" + oldName + "' → '" + newName + "' on " + replacementCount + " line(s).",
                Collections.singletonList(targetFile));
    }

    private boolean isValidIdentifier(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        char first = name.charAt(0);
        if (!Character.isJavaIdentifierStart(first)) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
