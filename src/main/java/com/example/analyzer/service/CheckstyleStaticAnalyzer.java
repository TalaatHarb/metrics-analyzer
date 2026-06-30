package com.example.analyzer.service;

import com.example.analyzer.model.StaticIssue;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CheckstyleStaticAnalyzer implements StaticAnalyzer {

    @Override
    public String getName() {
        return "Checkstyle Analyzer (Maven)";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null) return issues;

        try {
            String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
            ProcessBuilder pb = new ProcessBuilder(
                mvnCmd,
                "org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:checkstyle",
                "-Dcheckstyle.failOnViolation=false"
            );
            pb.directory(rootPath.toFile());

            // Redirect output to a temp file
            File tempLog = File.createTempFile("checkstyle-maven-log", ".txt");
            pb.redirectOutput(tempLog);
            pb.redirectError(tempLog);

            // Run Checkstyle
            Process process = pb.start();
            int exitCode = process.waitFor();

            File checkstyleReport = rootPath.resolve("target").resolve("checkstyle-result.xml").toFile();
            if (checkstyleReport.exists()) {
                DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                Document doc = dBuilder.parse(checkstyleReport);
                doc.getDocumentElement().normalize();

                NodeList fileList = doc.getElementsByTagName("file");
                for (int i = 0; i < fileList.getLength(); i++) {
                    Element fileElement = (Element) fileList.item(i);
                    String filePath = fileElement.getAttribute("name");
                    Path path = Path.of(filePath);

                    NodeList errorList = fileElement.getElementsByTagName("error");
                    for (int j = 0; j < errorList.getLength(); j++) {
                        Element error = (Element) errorList.item(j);
                        String lineStr = error.getAttribute("line");
                        int line = lineStr.isEmpty() ? 0 : Integer.parseInt(lineStr);
                        String message = error.getAttribute("message");
                        String severityAttr = error.getAttribute("severity");
                        
                        String severity = "Info";
                        if ("error".equalsIgnoreCase(severityAttr)) severity = "Error";
                        else if ("warning".equalsIgnoreCase(severityAttr)) severity = "Warning";

                        String source = error.getAttribute("source");
                        String rule = source;
                        if (source.contains(".")) {
                            rule = source.substring(source.lastIndexOf('.') + 1);
                        }

                        issues.add(new StaticIssue(path, line, "[" + rule + "] " + message, severity));
                    }
                }

                if (issues.isEmpty()) {
                    issues.add(new StaticIssue(rootPath, 0, "Checkstyle found 0 violations. Code is clean!", "Info"));
                }
            } else {
                if (exitCode != 0) {
                    String errorTail = "";
                    try {
                        List<String> lines = java.nio.file.Files.readAllLines(tempLog.toPath());
                        int start = Math.max(0, lines.size() - 5);
                        errorTail = " Log tail: " + String.join(" | ", lines.subList(start, lines.size()));
                    } catch (Exception ignore) {}
                    issues.add(new StaticIssue(rootPath, 0, "Checkstyle analysis failed (exit code " + exitCode + ")." + errorTail, "Error"));
                } else {
                    issues.add(new StaticIssue(rootPath, 0, "Checkstyle ran successfully but report was not generated.", "Warning"));
                }
            }
            tempLog.delete();
        } catch (Exception e) {
            issues.add(new StaticIssue(rootPath, 0, "Failed to run Checkstyle: " + e.getMessage(), "Error"));
        }
        return issues;
    }

    @Override
    public String toString() {
        return getName();
    }
}
