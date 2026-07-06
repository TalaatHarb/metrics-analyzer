package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalStaticAnalyzersTest {

    @Test
    void shouldHandleNullRootForPmdAnalyzer() {
        PMDStaticAnalyzer analyzer = new PMDStaticAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeProject(null);

        assertEquals("PMD Analyzer (Maven/Gradle)", analyzer.getName());
        assertEquals(analyzer.getName(), analyzer.toString());
        assertTrue(issues.isEmpty());
    }

    @Test
    void shouldHandleNullRootForCheckstyleAnalyzer() {
        CheckstyleStaticAnalyzer analyzer = new CheckstyleStaticAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeProject(null);

        assertEquals("Checkstyle Analyzer (Maven/Gradle)", analyzer.getName());
        assertEquals(analyzer.getName(), analyzer.toString());
        assertTrue(issues.isEmpty());
    }

    @Test
    void shouldHandleNullRootForSpotbugsAnalyzer() {
        SpotBugsStaticAnalyzer analyzer = new SpotBugsStaticAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeProject(null);

        assertEquals("SpotBugs Analyzer (Maven/Gradle)", analyzer.getName());
        assertEquals(analyzer.getName(), analyzer.toString());
        assertTrue(issues.isEmpty());
    }

    @Test
    void shouldReturnUnsupportedProjectTypeWarningForPmd(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("package.json"), "{ }");

        PMDStaticAnalyzer analyzer = new PMDStaticAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeProject(tempDir);

        assertEquals(1, issues.size());
        assertEquals("Warning", issues.get(0).getSeverity());
        assertTrue(issues.get(0).getDescription().contains("supports Maven or Gradle Java projects only"));
    }
}
