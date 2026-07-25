package net.talaatharb.analyzer.ui;

import net.talaatharb.analyzer.model.StaticIssue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

final class StaticIssueCsvExporter {
    private StaticIssueCsvExporter() {
    }

    static void export(
            Path targetFile,
            Path projectRoot,
            List<StaticIssue> issues,
            Function<StaticIssue, String> statusResolver
    ) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile must not be null");
        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                targetFile,
                buildCsvContent(projectRoot, issues, statusResolver),
                StandardCharsets.UTF_8
        );
    }

    static String buildCsvContent(
            Path projectRoot,
            List<StaticIssue> issues,
            Function<StaticIssue, String> statusResolver
    ) {
        List<StaticIssue> safeIssues = issues == null ? Collections.emptyList() : issues;
        Function<StaticIssue, String> safeStatusResolver = statusResolver == null ? issue -> "" : statusResolver;
        StringBuilder csv = new StringBuilder();
        csv.append("file,line,description,severity,status,category,ruleId,tool,confidence,fixability,suggestedFix,effort,tags")
                .append(System.lineSeparator());
        for (StaticIssue issue : safeIssues) {
            if (issue == null) {
                continue;
            }
            csv.append(csvCell(resolveFilePath(projectRoot, issue.getFile()))).append(',')
                    .append(csvCell(Integer.toString(issue.getLineNumber()))).append(',')
                    .append(csvCell(issue.getDescription())).append(',')
                    .append(csvCell(issue.getSeverity())).append(',')
                    .append(csvCell(safeStatusResolver.apply(issue))).append(',')
                    .append(csvCell(issue.getCategory())).append(',')
                    .append(csvCell(issue.getRuleId())).append(',')
                    .append(csvCell(issue.getTool())).append(',')
                    .append(csvCell(formatConfidence(issue))).append(',')
                    .append(csvCell(issue.getFixability())).append(',')
                    .append(csvCell(issue.getSuggestedFix())).append(',')
                    .append(csvCell(issue.getEffort())).append(',')
                    .append(csvCell(joinTags(issue)))
                    .append(System.lineSeparator());
        }
        return csv.toString();
    }

    private static String formatConfidence(StaticIssue issue) {
        double confidence = issue.getConfidence();
        if (!Double.isFinite(confidence)) {
            return "";
        }
        return String.format(Locale.US, "%.2f", confidence);
    }

    private static String joinTags(StaticIssue issue) {
        if (issue.getTags() == null || issue.getTags().isEmpty()) {
            return "";
        }
        return String.join("; ", issue.getTags());
    }

    private static String resolveFilePath(Path projectRoot, Path issueFile) {
        if (issueFile == null) {
            return "";
        }
        Path normalizedFile = issueFile.toAbsolutePath().normalize();
        if (projectRoot == null) {
            return normalizedFile.toString().replace('\\', '/');
        }

        try {
            return projectRoot.toAbsolutePath().normalize().relativize(normalizedFile).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return normalizedFile.toString().replace('\\', '/');
        }
    }

    private static String csvCell(String value) {
        String safeValue = value == null ? "" : value;
        boolean requiresQuotes = safeValue.contains(",")
                || safeValue.contains("\"")
                || safeValue.contains("\n")
                || safeValue.contains("\r");
        String escaped = safeValue.replace("\"", "\"\"");
        return requiresQuotes ? "\"" + escaped + "\"" : escaped;
    }
}
