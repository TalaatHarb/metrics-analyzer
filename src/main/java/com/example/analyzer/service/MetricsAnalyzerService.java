package com.example.analyzer.service;

import com.example.analyzer.model.AnalysisResult;

import java.nio.file.Path;

public interface MetricsAnalyzerService {
    String getDisplayName();

    boolean supports(Path projectRoot);

    AnalysisResult analyzeProject(Path projectRoot);
}
