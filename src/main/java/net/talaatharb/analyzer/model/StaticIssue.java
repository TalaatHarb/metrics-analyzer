package net.talaatharb.analyzer.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class StaticIssue {
    private final Path file;
    private final int lineNumber;
    private final String description;
    private final String severity;
    private final String category;
    private final String ruleId;
    private final String tool;
    private final double confidence;
    private final String fixability;
    private final String suggestedFix;
    private final String effort;
    private final List<String> tags;
    private final String status;

    public StaticIssue(Path file, int lineNumber, String description, String severity) {
        this(file, lineNumber, description, severity, "general", "", "", 0.0, "none", "", "unknown", List.of(), "open");
    }

    public StaticIssue(
            Path file,
            int lineNumber,
            String description,
            String severity,
            String category,
            String ruleId,
            String tool,
            double confidence,
            String fixability,
            String suggestedFix,
            String effort,
            List<String> tags,
            String status
    ) {
        this.file = file;
        this.lineNumber = lineNumber;
        this.description = description;
        this.severity = severity;
        this.category = category == null ? "general" : category;
        this.ruleId = ruleId == null ? "" : ruleId;
        this.tool = tool == null ? "" : tool;
        this.confidence = confidence;
        this.fixability = fixability == null ? "none" : fixability;
        this.suggestedFix = suggestedFix == null ? "" : suggestedFix;
        this.effort = effort == null ? "unknown" : effort;
        this.tags = tags == null ? Collections.emptyList() : List.copyOf(tags);
        this.status = status == null ? "open" : status;
    }

    public Path getFile() { return file; }
    public int getLineNumber() { return lineNumber; }
    public String getDescription() { return description; }
    public String getSeverity() { return severity; }
    public String getCategory() { return category; }
    public String getRuleId() { return ruleId; }
    public String getTool() { return tool; }
    public double getConfidence() { return confidence; }
    public String getFixability() { return fixability; }
    public String getSuggestedFix() { return suggestedFix; }
    public String getEffort() { return effort; }
    public List<String> getTags() { return tags; }
    public String getStatus() { return status; }

    public boolean hasQuickFix() {
        return "safe".equalsIgnoreCase(fixability) || "review".equalsIgnoreCase(fixability);
    }

    public String fingerprint(Path projectRoot) {
        String normalizedPath = normalizePathForFingerprint(projectRoot);
        String normalizedRuleId = ruleId == null ? "" : ruleId.trim().toLowerCase(Locale.ROOT);
        String normalizedDescription = description == null ? "" : description.trim();
        return normalizedPath + "|" + lineNumber + "|" + normalizedRuleId + "|" + normalizedDescription;
    }

    private String normalizePathForFingerprint(Path projectRoot) {
        if (file == null) {
            return "";
        }

        Path normalizedFile = file.toAbsolutePath().normalize();
        if (projectRoot == null) {
            return normalizedFile.toString().replace('\\', '/');
        }

        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        try {
            return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return normalizedFile.toString().replace('\\', '/');
        }
    }
}
