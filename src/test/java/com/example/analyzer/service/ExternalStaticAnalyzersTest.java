package com.example.analyzer.service;

import com.example.analyzer.model.StaticIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalStaticAnalyzersTest {

    @Test
    void shouldHandleNullRootForPmdAnalyzer() {
        PMDStaticAnalyzer analyzer = new PMDStaticAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeProject(null);

        assertEquals("PMD Analyzer (Maven)", analyzer.getName());
        assertEquals(analyzer.getName(), analyzer.toString());
        assertTrue(issues.isEmpty());
    }

    @Test
    void shouldHandleNullRootForCheckstyleAnalyzer() {
        CheckstyleStaticAnalyzer analyzer = new CheckstyleStaticAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeProject(null);

        assertEquals("Checkstyle Analyzer (Maven)", analyzer.getName());
        assertEquals(analyzer.getName(), analyzer.toString());
        assertTrue(issues.isEmpty());
    }

    @Test
    void shouldHandleNullRootForSpotbugsAnalyzer() {
        SpotBugsStaticAnalyzer analyzer = new SpotBugsStaticAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeProject(null);

        assertEquals("SpotBugs Analyzer (Maven)", analyzer.getName());
        assertEquals(analyzer.getName(), analyzer.toString());
        assertTrue(issues.isEmpty());
    }
}
