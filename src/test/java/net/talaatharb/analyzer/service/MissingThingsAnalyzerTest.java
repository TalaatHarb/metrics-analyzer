package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MissingThingsAnalyzerTest {

    private final MissingThingsAnalyzer analyzer = new MissingThingsAnalyzer();

    @Test
    void shouldReturnEmptyForNullOrMissingRoot() {
        assertTrue(analyzer.analyzeProject(null).isEmpty());
        assertTrue(analyzer.analyzeProject(Path.of("nonexistent-dir-xyz")).isEmpty());
    }

    @Test
    void shouldDetectMissingTestClass(@TempDir Path projectRoot) throws Exception {
        Path mainJava = projectRoot.resolve("src/main/java/com/example");
        Files.createDirectories(mainJava);
        Files.writeString(mainJava.resolve("Foo.java"),
                "package com.example;\npublic class Foo {}");
        // No corresponding test class created

        List<StaticIssue> issues = analyzer.analyzeProject(projectRoot);

        assertTrue(issues.stream().anyMatch(i ->
                "MISSING_TEST_CLASS".equals(i.getRuleId()) &&
                i.getDescription().contains("Foo")));
    }

    @Test
    void shouldNotReportTestClassMissingWhenTestExists(@TempDir Path projectRoot) throws Exception {
        Path mainJava = projectRoot.resolve("src/main/java/com/example");
        Path testJava = projectRoot.resolve("src/test/java/com/example");
        Files.createDirectories(mainJava);
        Files.createDirectories(testJava);
        Files.writeString(mainJava.resolve("Bar.java"),
                "package com.example;\npublic class Bar {}");
        Files.writeString(testJava.resolve("BarTest.java"),
                "package com.example;\nclass BarTest {}");

        List<StaticIssue> issues = analyzer.analyzeProject(projectRoot);

        assertTrue(issues.stream().noneMatch(i -> "MISSING_TEST_CLASS".equals(i.getRuleId())));
    }

    @Test
    void shouldDetectMissingJavadocOnPublicMethod(@TempDir Path projectRoot) throws Exception {
        Path mainJava = projectRoot.resolve("src/main/java/com/example");
        Files.createDirectories(mainJava);
        Files.writeString(mainJava.resolve("Service.java"), String.join(System.lineSeparator(),
                "package com.example;",
                "public class Service {",
                "    public String processData(String input) {",
                "        return input;",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeProject(projectRoot);

        assertTrue(issues.stream().anyMatch(i ->
                "MISSING_JAVADOC".equals(i.getRuleId()) &&
                i.getDescription().contains("processData")));
    }

    @Test
    void shouldNotReportJavadocMissingWhenPresent(@TempDir Path projectRoot) throws Exception {
        Path mainJava = projectRoot.resolve("src/main/java/com/example");
        Files.createDirectories(mainJava);
        Files.writeString(mainJava.resolve("Service.java"), String.join(System.lineSeparator(),
                "package com.example;",
                "public class Service {",
                "    /**",
                "     * Processes the data.",
                "     */",
                "    public String processData(String input) {",
                "        return input;",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeProject(projectRoot);

        assertTrue(issues.stream().noneMatch(i ->
                "MISSING_JAVADOC".equals(i.getRuleId()) &&
                i.getDescription().contains("processData")));
    }

    @Test
    void shouldDetectMissingLoggerFieldWhenSystemOutUsed(@TempDir Path projectRoot) throws Exception {
        Path mainJava = projectRoot.resolve("src/main/java/com/example");
        Files.createDirectories(mainJava);
        Files.writeString(mainJava.resolve("Printer.java"), String.join(System.lineSeparator(),
                "package com.example;",
                "public class Printer {",
                "    public void print(String msg) {",
                "        System.out.println(msg);",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeProject(projectRoot);

        assertTrue(issues.stream().anyMatch(i ->
                "MISSING_LOGGER_FIELD".equals(i.getRuleId())));
    }

    @Test
    void shouldNotReportMissingLoggerWhenLoggerAlreadyPresent(@TempDir Path projectRoot) throws Exception {
        Path mainJava = projectRoot.resolve("src/main/java/com/example");
        Files.createDirectories(mainJava);
        Files.writeString(mainJava.resolve("Printer.java"), String.join(System.lineSeparator(),
                "package com.example;",
                "import org.slf4j.Logger;",
                "import org.slf4j.LoggerFactory;",
                "public class Printer {",
                "    private static final Logger LOGGER = LoggerFactory.getLogger(Printer.class);",
                "    public void print(String msg) {",
                "        System.out.println(msg);",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeProject(projectRoot);

        assertTrue(issues.stream().noneMatch(i -> "MISSING_LOGGER_FIELD".equals(i.getRuleId())));
    }

    @Test
    void issuesShouldHaveCorrectAnalyzerName() {
        assertEquals("Missing Things Analyzer", analyzer.getName());
    }
}
