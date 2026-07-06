package net.talaatharb.analyzer.service.refactoring;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public final class RefactoringAction {
    private final RefactoringActionType type;
    private final Path targetFile;
    private final int lineNumber;
    private final Map<String, String> attributes;

    public RefactoringAction(RefactoringActionType type, Path targetFile, int lineNumber, Map<String, String> attributes) {
        this.type = type;
        this.targetFile = targetFile;
        this.lineNumber = lineNumber;
        this.attributes = attributes == null ? Collections.emptyMap() : Map.copyOf(attributes);
    }

    public RefactoringActionType getType() {
        return type;
    }

    public Path getTargetFile() {
        return targetFile;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
