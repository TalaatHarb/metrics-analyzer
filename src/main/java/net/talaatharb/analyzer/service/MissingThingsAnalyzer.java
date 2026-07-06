package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans the project for missing practices:
 * - Missing test class counterpart for each main class
 * - Public methods without Javadoc in non-test classes
 * - Classes that use System.out/err without a static Logger field
 */
public class MissingThingsAnalyzer implements StaticAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MissingThingsAnalyzer.class);

    private static final Pattern PUBLIC_METHOD_PATTERN = Pattern.compile(
            "^\\s*public\\s+(?:(?:static|final|synchronized|abstract|default)\\s+)*"
            + "(?!class\\b|interface\\b|enum\\b|@interface\\b)"
            + "[\\w<>\\[\\],?]+\\s+(\\w+)\\s*\\(");

    private static final Pattern LOGGER_FIELD_PATTERN = Pattern.compile(
            "private\\s+static\\s+(?:final\\s+)?(?:Logger|Log|SLF4JLogger)\\s+");

    @Override
    public String getName() {
        return "Missing Things Analyzer";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null || !Files.exists(rootPath)) {
            return issues;
        }

        Path mainJava = rootPath.resolve("src").resolve("main").resolve("java");
        Path testJava = rootPath.resolve("src").resolve("test").resolve("java");

        if (Files.isDirectory(mainJava)) {
            try (Stream<Path> paths = Files.walk(mainJava)) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.toString().endsWith(".java"))
                     .forEach(p -> analyzeMainFile(p, mainJava, testJava, issues));
            } catch (IOException e) {
                LOGGER.error("Error walking main sources at {}", mainJava, e);
            }
        }
        return issues;
    }

    private void analyzeMainFile(Path file, Path mainJava, Path testJava, List<StaticIssue> issues) {
        // Skip package-info.java
        if (file.getFileName().toString().equals("package-info.java")) {
            return;
        }

        checkMissingTestClass(file, mainJava, testJava, issues);

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Cannot read {}", file, e);
            return;
        }

        checkMissingJavadoc(file, lines, issues);
        checkMissingLogger(file, lines, issues);
    }

    private void checkMissingTestClass(Path file, Path mainJava, Path testJava, List<StaticIssue> issues) {
        Path relative = mainJava.relativize(file);
        String fileName = file.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - ".java".length());

        // Build expected test file path, e.g. FooTest.java in the same package directory
        Path testRelative = relative.getParent() != null
                ? relative.getParent().resolve(baseName + "Test.java")
                : Path.of(baseName + "Test.java");
        Path testFile = testJava.resolve(testRelative);

        if (!Files.exists(testFile)) {
            issues.add(createIssue(
                    file, 1,
                    "Missing test class: no test found for " + baseName
                            + " (expected " + testRelative + ")",
                    "Warning",
                    "MISSING_TEST_CLASS",
                    "none",
                    "Create a corresponding test class '" + baseName + "Test' in the test source tree.",
                    Arrays.asList("testing", "coverage", "missing-practice")
            ));
        }
    }

    private void checkMissingJavadoc(Path file, List<String> lines, List<StaticIssue> issues) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = PUBLIC_METHOD_PATTERN.matcher(line);
            if (!m.find()) {
                continue;
            }
            String methodName = m.group(1);
            // Skip constructors and getters/setters as lower priority
            if (methodName.equals("get") || methodName.equals("set") || methodName.equals("is")) {
                continue;
            }
            // Check the line before for Javadoc or annotation lines
            if (!hasJavadocBefore(lines, i)) {
                issues.add(createIssue(
                        file, i + 1,
                        "Public method '" + methodName + "' is missing a Javadoc comment",
                        "Info",
                        "MISSING_JAVADOC",
                        "none",
                        "Add a /** ... */ Javadoc comment describing the method's purpose and parameters.",
                        Arrays.asList("documentation", "maintainability", "missing-practice")
                ));
            }
        }
    }

    /**
     * Returns true if the lines preceding lineIndex (going up, skipping blank/annotation lines)
     * contain a Javadoc closing "&#42;/" token.
     */
    private boolean hasJavadocBefore(List<String> lines, int lineIndex) {
        for (int i = lineIndex - 1; i >= 0; i--) {
            String prev = lines.get(i).trim();
            if (prev.isEmpty() || prev.startsWith("@") || prev.startsWith("//")) {
                continue;
            }
            // Javadoc ends with */
            return prev.endsWith("*/");
        }
        return false;
    }

    private void checkMissingLogger(Path file, List<String> lines, List<StaticIssue> issues) {
        boolean usesSystemOut = false;
        boolean hasLoggerField = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("System.out.print") || trimmed.contains("System.err.print")) {
                usesSystemOut = true;
            }
            if (LOGGER_FIELD_PATTERN.matcher(trimmed).find()) {
                hasLoggerField = true;
            }
        }

        if (usesSystemOut && !hasLoggerField) {
            issues.add(createIssue(
                    file, 1,
                    "Class uses System.out/err without a static Logger field",
                    "Warning",
                    "MISSING_LOGGER_FIELD",
                    "none",
                    "Add 'private static final Logger LOGGER = LoggerFactory.getLogger(ClassName.class);' "
                            + "and replace System.out/err calls with LOGGER calls.",
                    Arrays.asList("logging", "maintainability", "missing-practice")
            ));
        }
    }

    private StaticIssue createIssue(Path file, int lineNum, String description,
            String severity, String ruleId, String fixability, String suggestedFix,
            List<String> tags) {
        return new StaticIssue(
                file,
                lineNum,
                description,
                severity,
                "missing-practice",  // category
                ruleId,
                getName(),           // tool
                0.70,                // confidence
                fixability,
                suggestedFix,
                "low",               // effort
                tags,
                "New"                // status
        );
    }
}
