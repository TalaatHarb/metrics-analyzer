package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BasicStaticAnalyzerTest {

    @Test
    void shouldAnalyzeSingleJavaFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Sample {",
                "  void run() {",
                "    System.out.println(\"x\");",
                "    // TODO fix this",
                "    while (true) {",
                "      break;",
                "    }",
                "  }",
                "}"));

        BasicStaticAnalyzer analyzer = new BasicStaticAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(analyzer.canAnalyzeSingleFile());
        assertEquals("Basic Analyzer", analyzer.getName());
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.getDescription().contains("System.out/err")));
        assertTrue(issues.stream().anyMatch(i -> i.getDescription().contains("TODO or FIXME")));
        assertTrue(issues.stream().anyMatch(i -> i.getDescription().contains("Infinite loop")));
    }

    @Test
    void shouldAnalyzeProjectAndIgnoreNonJavaFiles(@TempDir Path tempDir) throws Exception {
        Path javaFile = tempDir.resolve("A.java");
        Path textFile = tempDir.resolve("note.txt");
        Files.writeString(javaFile, "class A { void x(){ System.err.print(\"x\"); } }");
        Files.writeString(textFile, "System.out.println(\"not java\");");

        BasicStaticAnalyzer analyzer = new BasicStaticAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeProject(tempDir);

        assertTrue(issues.stream().allMatch(i -> i.getFile().toString().endsWith(".java")));
    }

    @Test
    void shouldReturnEmptyForInvalidInput() {
        BasicStaticAnalyzer analyzer = new BasicStaticAnalyzer();
        List<StaticIssue> nullResult = analyzer.analyzeProject(null);
        List<StaticIssue> nonJavaResult = analyzer.analyzeFile(Path.of("README.md"));

        assertTrue(nullResult.isEmpty());
        assertTrue(nonJavaResult.isEmpty());
    }
}
