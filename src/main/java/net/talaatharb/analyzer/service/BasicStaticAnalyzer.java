package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicStaticAnalyzer implements StaticAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(BasicStaticAnalyzer.class);
    
    @Override
    public String getName() {
        return "Basic Analyzer";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null || !Files.exists(rootPath)) return issues;
        
        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(p -> analyzeFileInternal(p, issues));
        } catch (IOException e) {
            LOGGER.error("Failed to scan project files at {}", rootPath, e);
        }
        return issues;
    }

    @Override
    public boolean canAnalyzeSingleFile() {
        return true;
    }

    @Override
    public List<StaticIssue> analyzeFile(Path filePath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (filePath != null && Files.isRegularFile(filePath) && filePath.toString().endsWith(".java")) {
            analyzeFileInternal(filePath, issues);
        }
        return issues;
    }
    
    private void analyzeFileInternal(Path file, List<StaticIssue> issues) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                int lineNum = i + 1;
                
                if (line.contains("System.out.print") || line.contains("System.err.print")) {
                    issues.add(createIssue(
                            file,
                            lineNum,
                            "Use of System.out/err instead of a proper logger",
                            "Warning",
                            "BASIC_SYSTEM_PRINT",
                            "review",
                            "Replace with structured logging statement and logger field.",
                            Arrays.asList("logging", "maintainability")
                    ));
                }
                if (line.contains("e.printStackTrace()")) {
                    issues.add(createIssue(
                            file,
                            lineNum,
                            "Avoid using e.printStackTrace(); use a logger",
                            "Warning",
                            "BASIC_PRINT_STACKTRACE",
                            "review",
                            "Replace with logger.error(\"message\", e).",
                            Arrays.asList("logging", "error-handling")
                    ));
                }
                if (line.contains("TODO") || line.contains("FIXME")) {
                    issues.add(createIssue(
                            file,
                            lineNum,
                            "Unresolved TODO or FIXME comment",
                            "Info",
                            "BASIC_TODO_FIXME",
                            "safe",
                            "Convert TODO/FIXME marker to NOTE or resolve the item.",
                            Arrays.asList("technical-debt", "cleanup")
                    ));
                }
                if ((line.startsWith("catch (") || line.startsWith("catch(")) && line.endsWith("{")) {
                    if (i + 1 < lines.size() && lines.get(i + 1).trim().equals("}")) {
                        issues.add(createIssue(
                                file,
                                lineNum,
                                "Empty catch block found",
                                "Error",
                                "BASIC_EMPTY_CATCH",
                                "review",
                                "Handle exception explicitly or rethrow with context.",
                                Arrays.asList("error-handling")
                        ));
                    }
                }
                if (line.equals("while (true) {") || line.equals("while(true){")) {
                     issues.add(createIssue(
                             file,
                             lineNum,
                             "Infinite loop construct found",
                             "Warning",
                             "BASIC_INFINITE_LOOP",
                             "review",
                             "Replace with explicit loop condition or bounded iteration.",
                             Arrays.asList("control-flow", "readability")
                     ));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Skipping unreadable file {}", file, e);
        }
    }

    private StaticIssue createIssue(
            Path file,
            int lineNum,
            String description,
            String severity,
            String ruleId,
            String fixability,
            String suggestedFix,
            List<String> tags
    ) {
        return new StaticIssue(
                file,
                lineNum,
                description,
                severity,
                "technical-debt",
                ruleId,
                getName(),
                0.9,
                fixability,
                suggestedFix,
                "small",
                tags,
                "open"
        );
    }

    @Override
    public String toString() {
        return getName();
    }
}
