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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CheckstyleStaticAnalyzer implements StaticAnalyzer {

    private static final String TOOL_NAME = "Checkstyle Analyzer (Maven)";

    /**
     * Known Checkstyle rule metadata: ruleSimpleName → [category, fixability, suggestedFix, effort]
     */
    private static final Map<String, String[]> KNOWN_RULE_META = Map.of(
        "NeedBraces",
            new String[]{"code-style", "safe",
                "Add curly braces around the block statement.", "small"},
        "MagicNumber",
            new String[]{"maintainability", "review",
                "Extract the magic number into a named constant.", "small"},
        "EmptyBlock",
            new String[]{"error-handling", "review",
                "Handle the empty block explicitly or add a comment explaining why it is empty.", "small"},
        "AvoidStarImport",
            new String[]{"code-style", "safe",
                "Replace the star import with explicit class imports.", "small"},
        "FinalLocalVariable",
            new String[]{"code-style", "safe",
                "Add the 'final' modifier to the local variable.", "small"},
        "WhitespaceAround",
            new String[]{"code-style", "safe",
                "Add required whitespace around the token.", "trivial"},
        "OneStatementPerLine",
            new String[]{"readability", "safe",
                "Move each statement to its own line.", "small"},
        "JavadocMethod",
            new String[]{"documentation", "none",
                "Add a Javadoc comment describing the method's purpose, parameters, and return value.", "medium"}
    );

    @Override
    public String getName() {
        return TOOL_NAME;
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

            File tempLog = File.createTempFile("checkstyle-maven-log", ".txt");
            pb.redirectOutput(tempLog);
            pb.redirectError(tempLog);

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

                        issues.add(buildIssue(path, line, rule, message, severity));
                    }
                }

                if (issues.isEmpty()) {
                    issues.add(new StaticIssue(rootPath, 0, "Checkstyle found 0 violations. Code is clean!", "Info"));
                }
            } else {
                if (exitCode != 0) {
                    String errorTail = "";
                    try {
                        List<String> logLines = java.nio.file.Files.readAllLines(tempLog.toPath());
                        int start = Math.max(0, logLines.size() - 5);
                        errorTail = " Log tail: " + String.join(" | ", logLines.subList(start, logLines.size()));
                    } catch (Exception ignore) {}
                    issues.add(new StaticIssue(rootPath, 0,
                            "Checkstyle analysis failed (exit code " + exitCode + ")." + errorTail, "Error"));
                } else {
                    issues.add(new StaticIssue(rootPath, 0,
                            "Checkstyle ran successfully but report was not generated.", "Warning"));
                }
            }
            tempLog.delete();
        } catch (Exception e) {
            issues.add(new StaticIssue(rootPath, 0, "Failed to run Checkstyle: " + e.getMessage(), "Error"));
        }
        return issues;
    }

    private StaticIssue buildIssue(Path file, int line, String rule, String message, String severity) {
        String[] meta = KNOWN_RULE_META.get(rule);
        if (meta != null) {
            return new StaticIssue(
                    file, line,
                    "[" + rule + "] " + message,
                    severity,
                    meta[0],      // category
                    rule,         // ruleId
                    TOOL_NAME,    // tool
                    0.85,         // confidence
                    meta[1],      // fixability
                    meta[2],      // suggestedFix
                    meta[3],      // effort
                    Arrays.asList("checkstyle"),
                    "New"
            );
        }
        return new StaticIssue(
                file, line,
                "[" + rule + "] " + message,
                severity,
                "general",    // category
                rule,         // ruleId
                TOOL_NAME,
                0.70,
                "none",
                "No suggested fix available.",
                "unknown",
                Arrays.asList("checkstyle"),
                "New"
        );
    }

    @Override
    public String toString() {
        return getName();
    }
}
