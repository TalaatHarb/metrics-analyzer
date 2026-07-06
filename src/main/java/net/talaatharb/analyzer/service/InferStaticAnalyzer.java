package net.talaatharb.analyzer.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.talaatharb.analyzer.model.StaticIssue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InferStaticAnalyzer implements StaticAnalyzer {

    @Override
    public String getName() {
        return "Infer Analyzer";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null) return issues;

        ServicePackageStaticAnalyzerSupport.ProcessExecution execution = null;
        try {
            execution = ServicePackageStaticAnalyzerSupport.runCommand(
                rootPath,
                "infer-analysis-log",
                Arrays.asList(
                    "infer",
                    "run",
                    "--results-dir",
                    "infer-out",
                    "--",
                    ServicePackageStaticAnalyzerSupport.getMavenCommand(),
                    "-DskipTests",
                    "compile"
                )
            );

            Path reportPath = rootPath.resolve("infer-out").resolve("report.json");
            if (!Files.exists(reportPath)) {
                String logTail = ServicePackageStaticAnalyzerSupport.getLogTail(execution.getLogFile(), 8);
                issues.add(new StaticIssue(
                    rootPath,
                    0,
                    "Infer scan did not produce infer-out/report.json. Ensure Infer is installed. " + logTail,
                    execution.getExitCode() == 0 ? "Warning" : "Error"
                ));
                return issues;
            }

            JsonElement jsonElement = JsonParser.parseString(Files.readString(reportPath));
            if (!jsonElement.isJsonArray()) {
                issues.add(new StaticIssue(rootPath, 0, "Infer report format is invalid.", "Error"));
                return issues;
            }

            JsonArray findings = jsonElement.getAsJsonArray();
            for (JsonElement finding : findings) {
                if (!finding.isJsonObject()) continue;
                JsonObject obj = finding.getAsJsonObject();

                String file = getString(obj, "file");

                int line = obj.has("line") && obj.get("line").isJsonPrimitive()
                    ? obj.get("line").getAsInt()
                    : 0;
                String bugType = getString(obj, "bug_type");
                String qualifier = getString(obj, "qualifier");
                String severity = mapSeverity(getString(obj, "severity"));
                Path resolvedPath = ServicePackageStaticAnalyzerSupport.resolveSourcePath(rootPath, file);

                issues.add(new StaticIssue(
                    resolvedPath,
                    line,
                    "[Infer][" + bugType + "] " + qualifier,
                    severity
                ));
            }

            if (issues.isEmpty()) {
                issues.add(new StaticIssue(rootPath, 0, "No Infer findings in the analyzed project.", "Info"));
            }
        } catch (Exception e) {
            issues.add(new StaticIssue(rootPath, 0, "Failed to run Infer scan: " + e.getMessage(), "Error"));
        } finally {
            if (execution != null) {
                ServicePackageStaticAnalyzerSupport.deleteFileQuietly(execution.getLogFile());
            }
        }
        return issues;
    }

    private static String getString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return "";
        return object.get(key).getAsString();
    }

    private static String mapSeverity(String severity) {
        if (severity == null) return "Warning";
        String normalized = severity.trim().toUpperCase();
        if ("ERROR".equals(normalized)) return "Error";
        if ("INFO".equals(normalized)) return "Info";
        return "Warning";
    }

    @Override
    public String toString() {
        return getName();
    }
}
