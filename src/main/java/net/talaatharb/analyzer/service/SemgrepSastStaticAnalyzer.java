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

public class SemgrepSastStaticAnalyzer implements StaticAnalyzer {

    @Override
    public String getName() {
        return "SAST Analyzer (Semgrep)";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null) return issues;

        Path servicePath = ServicePackageStaticAnalyzerSupport.getServicePackagePath(rootPath);
        if (!Files.isDirectory(servicePath)) {
            issues.add(new StaticIssue(rootPath, 0, "Service package not found at " + servicePath, "Warning"));
            return issues;
        }

        ServicePackageStaticAnalyzerSupport.ProcessExecution execution = null;
        try {
            execution = ServicePackageStaticAnalyzerSupport.runCommand(
                rootPath,
                "semgrep-sast-log",
                Arrays.asList(
                    "semgrep",
                    "scan",
                    "--config",
                    "auto",
                    "--quiet",
                    "--json",
                    servicePath.toString()
                )
            );

            String output = Files.readString(execution.getLogFile());
            JsonElement root = JsonParser.parseString(output);
            if (!root.isJsonObject()) {
                issues.add(new StaticIssue(rootPath, 0, "Semgrep output format is invalid.", "Error"));
                return issues;
            }

            JsonArray results = root.getAsJsonObject().has("results")
                ? root.getAsJsonObject().getAsJsonArray("results")
                : new JsonArray();

            for (JsonElement resultElement : results) {
                if (!resultElement.isJsonObject()) continue;
                JsonObject result = resultElement.getAsJsonObject();

                String filePath = getString(result, "path");
                if (!ServicePackageStaticAnalyzerSupport.isServicePackageFile(filePath)) {
                    continue;
                }

                int line = 0;
                if (result.has("start") && result.get("start").isJsonObject()) {
                    JsonObject start = result.getAsJsonObject("start");
                    if (start.has("line") && start.get("line").isJsonPrimitive()) {
                        line = start.get("line").getAsInt();
                    }
                }

                JsonObject extra = result.has("extra") && result.get("extra").isJsonObject()
                    ? result.getAsJsonObject("extra")
                    : new JsonObject();
                String checkId = getString(result, "check_id");
                String message = getString(extra, "message");
                String severity = mapSeverity(getString(extra, "severity"));

                issues.add(new StaticIssue(Path.of(filePath), line, "[Semgrep][" + checkId + "] " + message, severity));
            }

            if (issues.isEmpty()) {
                issues.add(new StaticIssue(servicePath, 0, "No Semgrep SAST findings in the service package.", "Info"));
            }
        } catch (Exception e) {
            issues.add(new StaticIssue(
                rootPath,
                0,
                "Failed to run Semgrep SAST scan: " + e.getMessage() + ". Ensure semgrep is installed.",
                "Error"
            ));
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
        if ("ERROR".equals(normalized) || "HIGH".equals(normalized)) return "Error";
        if ("INFO".equals(normalized) || "LOW".equals(normalized)) return "Info";
        return "Warning";
    }

    @Override
    public String toString() {
        return getName();
    }
}
