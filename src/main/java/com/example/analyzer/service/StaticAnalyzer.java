package com.example.analyzer.service;

import com.example.analyzer.model.StaticIssue;
import java.nio.file.Path;
import java.util.List;

public interface StaticAnalyzer {
    String getName();
    List<StaticIssue> analyzeProject(Path rootPath);
}