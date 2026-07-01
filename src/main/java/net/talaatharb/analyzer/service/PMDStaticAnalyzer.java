package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PMDStaticAnalyzer implements StaticAnalyzer {

    @Override
    public String getName() {
        return "PMD Analyzer (Maven)";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null) return issues;
        
        try {
            String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
            ProcessBuilder pb = new ProcessBuilder(
                mvnCmd, 
                "org.apache.maven.plugins:maven-pmd-plugin:3.28.0:pmd", 
                "-Dpmd.failOnViolation=false",
                "-Dpmd.skipEmptyReport=false"
            );
            pb.directory(rootPath.toFile());
            
            // Redirect output to a temp file to avoid buffer blocking and allow inspecting errors
            File tempLog = File.createTempFile("pmd-maven-log", ".txt");
            pb.redirectOutput(tempLog);
            pb.redirectError(tempLog);
            
            // Run PMD
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            File pmdReport = rootPath.resolve("target").resolve("pmd.xml").toFile();
            if (pmdReport.exists()) {
                DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                Document doc = dBuilder.parse(pmdReport);
                doc.getDocumentElement().normalize();
                
                NodeList fileList = doc.getElementsByTagName("file");
                for (int i = 0; i < fileList.getLength(); i++) {
                    Element fileElement = (Element) fileList.item(i);
                    String filePath = fileElement.getAttribute("name");
                    Path path = Path.of(filePath);
                    
                    NodeList violationList = fileElement.getElementsByTagName("violation");
                    for (int j = 0; j < violationList.getLength(); j++) {
                        Element violation = (Element) violationList.item(j);
                        int line = Integer.parseInt(violation.getAttribute("beginline"));
                        String rule = violation.getAttribute("rule");
                        String description = violation.getTextContent().trim();
                        int priority = Integer.parseInt(violation.getAttribute("priority"));
                        
                        String severity = priority <= 2 ? "Error" : (priority <= 4 ? "Warning" : "Info");
                        issues.add(new StaticIssue(path, line, "[" + rule + "] " + description, severity));
                    }
                }
                
                if (issues.isEmpty()) {
                    issues.add(new StaticIssue(rootPath, 0, "PMD found 0 violations. Code is clean!", "Info"));
                }
            } else {
                if (exitCode != 0) {
                    String errorTail = "";
                    try {
                        List<String> lines = java.nio.file.Files.readAllLines(tempLog.toPath());
                        int start = Math.max(0, lines.size() - 5);
                        errorTail = " Log tail: " + String.join(" | ", lines.subList(start, lines.size()));
                    } catch (Exception ignore) {}
                    issues.add(new StaticIssue(rootPath, 0, "PMD analysis failed (exit code " + exitCode + ")." + errorTail, "Error"));
                } else {
                    issues.add(new StaticIssue(rootPath, 0, "PMD ran successfully but report was not generated.", "Warning"));
                }
            }
            tempLog.delete();
        } catch (Exception e) {
            issues.add(new StaticIssue(rootPath, 0, "Failed to run PMD: " + e.getMessage(), "Error"));
        }
        return issues;
    }

    @Override
    public String toString() {
        return getName();
    }
}