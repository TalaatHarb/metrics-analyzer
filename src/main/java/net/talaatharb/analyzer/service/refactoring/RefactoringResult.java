package net.talaatharb.analyzer.service.refactoring;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RefactoringResult {
    private final boolean modified;
    private final String message;
    private final List<Path> modifiedFiles;

    private RefactoringResult(boolean modified, String message, List<Path> modifiedFiles) {
        this.modified = modified;
        this.message = message == null ? "" : message;
        this.modifiedFiles = Collections.unmodifiableList(new ArrayList<>(modifiedFiles));
    }

    public static RefactoringResult modified(String message, List<Path> modifiedFiles) {
        return new RefactoringResult(true, message, modifiedFiles == null ? Collections.emptyList() : modifiedFiles);
    }

    public static RefactoringResult noChange(String message) {
        return new RefactoringResult(false, message, Collections.emptyList());
    }

    public boolean isModified() {
        return modified;
    }

    public String getMessage() {
        return message;
    }

    public List<Path> getModifiedFiles() {
        return modifiedFiles;
    }
}
