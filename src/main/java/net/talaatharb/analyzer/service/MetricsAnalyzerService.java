package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.AnalysisResult;

import java.nio.file.Path;

public interface MetricsAnalyzerService {
    String getDisplayName();

    boolean supports(Path projectRoot);

    AnalysisResult analyzeProject(Path projectRoot);
}
