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

public class SpotBugsStaticAnalyzer implements StaticAnalyzer {

    @Override
    public String getName() {
        return "SpotBugs Analyzer (Maven)";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null) return issues;

        try {
            String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
            ProcessBuilder pb = new ProcessBuilder(
                mvnCmd,
                "compile",
                "com.github.spotbugs:spotbugs-maven-plugin:4.10.2.0:spotbugs",
                "-Dspotbugs.failOnError=false"
            );
            pb.directory(rootPath.toFile());

            // Redirect output to a temp file
            File tempLog = File.createTempFile("spotbugs-maven-log", ".txt");
            pb.redirectOutput(tempLog);
            pb.redirectError(tempLog);

            // Run SpotBugs
            Process process = pb.start();
            int exitCode = process.waitFor();

            File spotbugsReport = rootPath.resolve("target").resolve("spotbugsXml.xml").toFile();
            if (spotbugsReport.exists()) {
                DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                Document doc = dBuilder.parse(spotbugsReport);
                doc.getDocumentElement().normalize();

                NodeList bugList = doc.getElementsByTagName("BugInstance");
                for (int i = 0; i < bugList.getLength(); i++) {
                    Element bug = (Element) bugList.item(i);
                    String type = bug.getAttribute("type");
                    String category = bug.getAttribute("category");
                    int priority = Integer.parseInt(bug.getAttribute("priority"));
                    
                    String severity = "Info";
                    if (priority == 1) severity = "Error";
                    else if (priority == 2) severity = "Warning";

                    String message = type;
                    NodeList longMessageList = bug.getElementsByTagName("LongMessage");
                    if (longMessageList.getLength() > 0) {
                        message = longMessageList.item(0).getTextContent().trim();
                    } else {
                        NodeList shortMessageList = bug.getElementsByTagName("ShortMessage");
                        if (shortMessageList.getLength() > 0) {
                            message = shortMessageList.item(0).getTextContent().trim();
                        }
                    }

                    int line = 0;
                    Path path = rootPath;

                    NodeList sourceLineList = bug.getElementsByTagName("SourceLine");
                    for (int j = 0; j < sourceLineList.getLength(); j++) {
                        Element sourceLine = (Element) sourceLineList.item(j);
                        // We prefer the primary SourceLine or just take the first one found inside the BugInstance (not inside Class/Method)
                        if (sourceLine.getParentNode() == bug || "true".equals(sourceLine.getAttribute("primary"))) {
                            String startLine = sourceLine.getAttribute("start");
                            if (!startLine.isEmpty()) {
                                line = Integer.parseInt(startLine);
                            }
                            String sourcePath = sourceLine.getAttribute("sourcepath");
                            if (!sourcePath.isEmpty()) {
                                // Try main and test
                                Path mainPath = rootPath.resolve("src").resolve("main").resolve("java").resolve(sourcePath);
                                Path testPath = rootPath.resolve("src").resolve("test").resolve("java").resolve(sourcePath);
                                if (mainPath.toFile().exists()) {
                                    path = mainPath;
                                } else if (testPath.toFile().exists()) {
                                    path = testPath;
                                } else {
                                    path = Path.of(sourcePath);
                                }
                            }
                            break;
                        }
                    }

                    issues.add(new StaticIssue(path, line, "[" + category + "] " + message, severity));
                }

                if (issues.isEmpty()) {
                    issues.add(new StaticIssue(rootPath, 0, "SpotBugs found 0 violations. Code is clean!", "Info"));
                }
            } else {
                if (exitCode != 0) {
                    String errorTail = "";
                    try {
                        List<String> lines = java.nio.file.Files.readAllLines(tempLog.toPath());
                        int start = Math.max(0, lines.size() - 5);
                        errorTail = " Log tail: " + String.join(" | ", lines.subList(start, lines.size()));
                    } catch (Exception ignore) {}
                    issues.add(new StaticIssue(rootPath, 0, "SpotBugs analysis failed (exit code " + exitCode + ")." + errorTail, "Error"));
                } else {
                    issues.add(new StaticIssue(rootPath, 0, "SpotBugs ran successfully but report was not generated.", "Warning"));
                }
            }
            tempLog.delete();
        } catch (Exception e) {
            issues.add(new StaticIssue(rootPath, 0, "Failed to run SpotBugs: " + e.getMessage(), "Error"));
        }
        return issues;
    }

    @Override
    public String toString() {
        return getName();
    }
}
