package net.talaatharb.analyzer.ui;

import net.talaatharb.analyzer.model.StaticIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticIssueCsvExporterTest {

    @Test
    void shouldBuildCsvWithRelativePathAndEscapedFields(@TempDir Path tempDir) {
        Path root = tempDir.resolve("project");
        Path file = root.resolve("src/main/java/Sample.java");
        StaticIssue issue = new StaticIssue(
                file,
                12,
                "Needs \"escaping\", with comma",
                "HIGH",
                "style",
                "RULE-1",
                "PMD",
                0.95,
                "safe",
                "Replace line\nwith better code",
                "low",
                List.of("cleanup", "readability"),
                "open"
        );

        String csv = StaticIssueCsvExporter.buildCsvContent(root, List.of(issue), ignored -> "New");

        String[] lines = csv.split("\\R");
        assertEquals("file,line,description,severity,status,category,ruleId,tool,confidence,fixability,suggestedFix,effort,tags", lines[0]);
        assertTrue(lines[1].startsWith("src/main/java/Sample.java,12,"));
        assertTrue(lines[1].contains("\"Needs \"\"escaping\"\", with comma\""));
        assertTrue(lines[1].contains(",New,"));
        assertTrue(lines[1].contains("\"Replace line"));
        assertTrue(lines[1].endsWith("cleanup; readability"));
    }

    @Test
    void shouldWriteCsvFile(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("reports/issues.csv");
        StaticIssue issue = new StaticIssue(tempDir.resolve("A.java"), 3, "Issue", "LOW");

        StaticIssueCsvExporter.export(target, tempDir, List.of(issue), ignored -> "Existing");

        assertTrue(Files.isRegularFile(target));
        String csv = Files.readString(target);
        assertTrue(csv.contains("A.java,3,Issue,LOW,Existing"));
    }
}
