package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JQAssistantStaticAnalyzer implements StaticAnalyzer {

    @Override
    public String getName() {
        return "jQAssistant Analyzer";
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
                "jqassistant-scan-log",
                Arrays.asList(
                    ServicePackageStaticAnalyzerSupport.getMavenCommand(),
                    "com.buschmais.jqassistant:jqassistant-maven-plugin:2.6.0:scan"
                )
            );

            String logTail = ServicePackageStaticAnalyzerSupport.getLogTail(execution.getLogFile(), 8);
            if (execution.getExitCode() != 0) {
                issues.add(new StaticIssue(rootPath, 0, "jQAssistant scan failed. " + logTail, "Error"));
                return issues;
            }

            issues.add(new StaticIssue(
                rootPath,
                0,
                "jQAssistant scan completed for the project. Add jQAssistant rules to produce actionable findings.",
                "Info"
            ));
        } catch (Exception e) {
            issues.add(new StaticIssue(
                rootPath,
                0,
                "Failed to run jQAssistant scan: " + e.getMessage(),
                "Error"
            ));
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
