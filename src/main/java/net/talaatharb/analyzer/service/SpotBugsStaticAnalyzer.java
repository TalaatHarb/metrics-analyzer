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
import java.util.Map;

public class SpotBugsStaticAnalyzer implements StaticAnalyzer {
    private static final String TOOL_NAME = "SpotBugs Analyzer (Maven)";
    private static final Map<String, RuleMeta> KNOWN_RULE_META = Map.of(
            "NP_NULL_ON_SOME_PATH", new RuleMeta("correctness", "review",
                    "Add null-guard checks or validate the object before use.", "medium", List.of("null-safety", "spotbugs")),
            "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", new RuleMeta("correctness", "review",
                    "Check return value for null before dereference.", "medium", List.of("null-safety", "spotbugs")),
            "DLS_DEAD_LOCAL_STORE", new RuleMeta("technical-debt", "safe",
                    "Remove dead local assignment or use the assigned value.", "small", List.of("cleanup", "spotbugs")),
            "URF_UNREAD_FIELD", new RuleMeta("technical-debt", "safe",
                    "Remove unused field or wire it into behavior.", "small", List.of("cleanup", "spotbugs")),
            "EI_EXPOSE_REP", new RuleMeta("security", "review",
                    "Avoid exposing mutable internal state; return immutable copies.", "medium", List.of("encapsulation", "spotbugs")),
            "EI_EXPOSE_REP2", new RuleMeta("security", "review",
                    "Do not retain externally mutable references directly.", "medium", List.of("encapsulation", "spotbugs")),
            "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE", new RuleMeta("security", "review",
                    "Use parameterized queries to avoid SQL injection risk.", "large", List.of("security", "injection", "spotbugs"))
    );
    private static final Map<String, RuleMeta> KNOWN_CATEGORY_META = Map.of(
            "SECURITY", new RuleMeta("security", "review",
                    "Address security-sensitive pattern and validate exploitability.", "medium", List.of("security", "spotbugs")),
            "MALICIOUS_CODE", new RuleMeta("security", "review",
                    "Review potentially unsafe operations and reduce attack surface.", "medium", List.of("security", "spotbugs")),
            "PERFORMANCE", new RuleMeta("performance", "review",
                    "Optimize the expensive operation or remove unnecessary allocations.", "small", List.of("performance", "spotbugs")),
            "STYLE", new RuleMeta("code-style", "safe",
                    "Apply style/cleanup recommendation.", "small", List.of("style", "spotbugs")),
            "BAD_PRACTICE", new RuleMeta("maintainability", "review",
                    "Refactor bad practice into clearer and safer code.", "medium", List.of("maintainability", "spotbugs")),
            "CORRECTNESS", new RuleMeta("correctness", "review",
                    "Fix correctness issue to prevent runtime defects.", "medium", List.of("correctness", "spotbugs")),
            "MT_CORRECTNESS", new RuleMeta("correctness", "review",
                    "Fix thread-safety/concurrency correctness issue.", "large", List.of("concurrency", "spotbugs"))
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

                    issues.add(buildIssue(path, line, type, category, message, severity, priority));
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

    private StaticIssue buildIssue(
            Path file,
            int line,
            String type,
            String category,
            String message,
            String severity,
            int priority
    ) {
        RuleMeta categoryMeta = category == null ? null : KNOWN_CATEGORY_META.get(category.toUpperCase());
        RuleMeta ruleMeta = type == null ? null : KNOWN_RULE_META.get(type);
        RuleMeta meta = ruleMeta != null ? ruleMeta : (categoryMeta != null ? categoryMeta : RuleMeta.UNKNOWN);
        String categoryLabel = meta.category;
        String ruleId = type == null ? "" : type;
        double confidence = priority <= 1 ? 0.95 : priority == 2 ? 0.85 : 0.70;
        return new StaticIssue(
                file,
                line,
                "[" + ruleId + "] " + message,
                severity,
                categoryLabel,
                ruleId,
                TOOL_NAME,
                confidence,
                meta.fixability,
                meta.suggestedFix,
                meta.effort,
                meta.tags,
                "open"
        );
    }

    private static final class RuleMeta {
        static final RuleMeta UNKNOWN = new RuleMeta(
                "general",
                "none",
                "No suggested fix available.",
                "unknown",
                List.of("spotbugs")
        );
        final String category;
        final String fixability;
        final String suggestedFix;
        final String effort;
        final List<String> tags;

        RuleMeta(String category, String fixability, String suggestedFix, String effort, List<String> tags) {
            this.category = category;
            this.fixability = fixability;
            this.suggestedFix = suggestedFix;
            this.effort = effort;
            this.tags = tags;
        }
    }
}
