package com.example.analyzer.service;

import com.example.analyzer.model.AnalysisResult;
import com.example.analyzer.model.DependencyRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceProjectAnalyzerTest {

    @Test
    void shouldSupportMavenStyleProjectWithJavaFiles(@TempDir Path tempDir) throws Exception {
        Path srcMainJava = tempDir.resolve("src").resolve("main").resolve("java").resolve("a");
        Files.createDirectories(srcMainJava);
        Files.writeString(srcMainJava.resolve("App.java"), "package a; class App {}");

        JavaSourceProjectAnalyzer analyzer = new JavaSourceProjectAnalyzer();
        assertTrue(analyzer.supports(tempDir));
    }

    @Test
    void shouldNotSupportDirectoryWithoutJavaFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("notes.txt"), "hello");

        JavaSourceProjectAnalyzer analyzer = new JavaSourceProjectAnalyzer();
        assertFalse(analyzer.supports(tempDir));
    }

    @Test
    void shouldAnalyzeProjectAndBuildCouplings(@TempDir Path tempDir) throws Exception {
        Path srcMainJava = tempDir.resolve("src").resolve("main").resolve("java");
        Path p1 = srcMainJava.resolve("p1");
        Path p2 = srcMainJava.resolve("p2");
        Files.createDirectories(p1);
        Files.createDirectories(p2);

        Files.writeString(p2.resolve("B.java"), String.join(System.lineSeparator(),
                "package p2;",
                "public class B {",
                "  public void ping() {}",
                "}"));

        Files.writeString(p1.resolve("A.java"), String.join(System.lineSeparator(),
                "package p1;",
                "import p2.B;",
                "public class A {",
                "  private B b = new B();",
                "  public void run(){",
                "    if (true) { b.ping(); }",
                "  }",
                "}"));

        JavaSourceProjectAnalyzer analyzer = new JavaSourceProjectAnalyzer();
        AnalysisResult result = analyzer.analyzeProject(tempDir);
        List<DependencyRelation> classCouplings = result.getClassCouplings();
        List<DependencyRelation> packageCouplings = result.getPackageCouplings();

        assertFalse(result.getClassMetrics().isEmpty());
        assertTrue(classCouplings.stream().anyMatch(c -> "p1.A".equals(c.getSource()) && "p2.B".equals(c.getTarget())));
        assertTrue(packageCouplings.stream().anyMatch(c -> "p1".equals(c.getSource()) && "p2".equals(c.getTarget())));
    }

    @Test
    void shouldThrowWhenSourcePathDoesNotExist(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("missing");
        JavaSourceProjectAnalyzer analyzer = new JavaSourceProjectAnalyzer();

        assertThrows(IllegalArgumentException.class, () -> analyzer.analyzeProject(missing));
    }
}
