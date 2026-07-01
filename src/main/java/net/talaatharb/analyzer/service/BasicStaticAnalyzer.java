package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class BasicStaticAnalyzer implements StaticAnalyzer {
    
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
            e.printStackTrace();
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
                    issues.add(new StaticIssue(file, lineNum, "Use of System.out/err instead of a proper logger", "Warning"));
                }
                if (line.contains("e.printStackTrace()")) {
                    issues.add(new StaticIssue(file, lineNum, "Avoid using e.printStackTrace(); use a logger", "Warning"));
                }
                if (line.contains("TODO") || line.contains("FIXME")) {
                    issues.add(new StaticIssue(file, lineNum, "Unresolved TODO or FIXME comment", "Info"));
                }
                if ((line.startsWith("catch (") || line.startsWith("catch(")) && line.endsWith("{")) {
                    if (i + 1 < lines.size() && lines.get(i + 1).trim().equals("}")) {
                        issues.add(new StaticIssue(file, lineNum, "Empty catch block found", "Error"));
                    }
                }
                if (line.equals("while (true) {") || line.equals("while(true){")) {
                     issues.add(new StaticIssue(file, lineNum, "Infinite loop construct found", "Warning"));
                }
            }
        } catch (IOException e) {
            // Skip unreadable files
        }
    }

    @Override
    public String toString() {
        return getName();
    }
}