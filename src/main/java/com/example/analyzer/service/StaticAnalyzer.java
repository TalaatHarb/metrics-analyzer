package com.example.analyzer.service;

import com.example.analyzer.model.StaticIssue;
import java.nio.file.Path;
import java.util.List;

public interface StaticAnalyzer {
    String getName();
    List<StaticIssue> analyzeProject(Path rootPath);
    
    default boolean canAnalyzeSingleFile() {
        return false;
    }
    
    default List<StaticIssue> analyzeFile(Path filePath) {
        throw new UnsupportedOperationException("This analyzer does not support single file analysis.");
    }
}