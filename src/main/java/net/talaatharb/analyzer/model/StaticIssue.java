package net.talaatharb.analyzer.model;

import java.nio.file.Path;

public class StaticIssue {
    private final Path file;
    private final int lineNumber;
    private final String description;
    private final String severity;

    public StaticIssue(Path file, int lineNumber, String description, String severity) {
        this.file = file;
        this.lineNumber = lineNumber;
        this.description = description;
        this.severity = severity;
    }

    public Path getFile() { return file; }
    public int getLineNumber() { return lineNumber; }
    public String getDescription() { return description; }
    public String getSeverity() { return severity; }
}
