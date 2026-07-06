package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FindBugsStaticAnalyzer implements StaticAnalyzer {

    @Override
    public String getName() {
        return "FindBugs Analyzer (SpotBugs-compatible)";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null) return issues;
        if (!ServicePackageStaticAnalyzerSupport.supportsJavaBuildAnalysis(rootPath)) {
            issues.add(ServicePackageStaticAnalyzerSupport.unsupportedJavaBuildProjectIssue(getName(), rootPath));
            return issues;
        }

        ServicePackageStaticAnalyzerSupport.ProcessExecution execution = null;
        try {
            execution = ServicePackageStaticAnalyzerSupport.runCommand(
                rootPath,
                "findbugs-maven-log",
                Arrays.asList(
                    ServicePackageStaticAnalyzerSupport.getMavenCommand(),
                    "compile",
                    "com.github.spotbugs:spotbugs-maven-plugin:4.10.2.0:spotbugs",
                    "-Dspotbugs.failOnError=false"
                )
            );

            Path reportPath = rootPath.resolve("target").resolve("spotbugsXml.xml");
            if (!Files.exists(reportPath)) {
                String logTail = ServicePackageStaticAnalyzerSupport.getLogTail(execution.getLogFile(), 8);
                issues.add(new StaticIssue(
                    rootPath,
                    0,
                    "FindBugs-compatible scan did not produce target/spotbugsXml.xml. " + logTail,
                    execution.getExitCode() == 0 ? "Warning" : "Error"
                ));
                return issues;
            }

            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(reportPath.toFile());
            doc.getDocumentElement().normalize();

            NodeList bugList = doc.getElementsByTagName("BugInstance");
            for (int i = 0; i < bugList.getLength(); i++) {
                Element bug = (Element) bugList.item(i);

                NodeList sourceLineList = bug.getElementsByTagName("SourceLine");
                Path sourcePath = null;
                int line = 0;
                for (int j = 0; j < sourceLineList.getLength(); j++) {
                    Element sourceLine = (Element) sourceLineList.item(j);
                    String pathText = sourceLine.getAttribute("sourcepath");
                    if (!pathText.isEmpty()) {
                        Path candidate = ServicePackageStaticAnalyzerSupport.resolveSourcePath(rootPath, pathText);
                        sourcePath = candidate;
                        String startLine = sourceLine.getAttribute("start");
                        if (!startLine.isEmpty()) {
                            line = Integer.parseInt(startLine);
                        }
                        break;
                    }
                }
                if (sourcePath == null) {
                    continue;
                }

                String category = bug.getAttribute("category");
                String message = bug.getAttribute("type");
                NodeList longMessageList = bug.getElementsByTagName("LongMessage");
                if (longMessageList.getLength() > 0) {
                    message = longMessageList.item(0).getTextContent().trim();
                }

                String severity = "Info";
                String priority = bug.getAttribute("priority");
                if ("1".equals(priority)) severity = "Error";
                else if ("2".equals(priority)) severity = "Warning";

                issues.add(new StaticIssue(sourcePath, line, "[" + category + "] " + message, severity));
            }

            if (issues.isEmpty()) {
                issues.add(new StaticIssue(rootPath, 0, "No FindBugs-compatible findings in the analyzed project.", "Info"));
            }
        } catch (Exception e) {
            issues.add(new StaticIssue(rootPath, 0, "Failed to run FindBugs-compatible scan: " + e.getMessage(), "Error"));
        } finally {
            if (execution != null) {
                ServicePackageStaticAnalyzerSupport.deleteFileQuietly(execution.getLogFile());
            }
        }
        return issues;
    }

    @Override
    public String toString() {
        return getName();
    }
}
