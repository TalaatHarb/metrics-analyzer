package com.example.analyzer.model;

public final class DependencyRelation {
    private final String source;
    private final String target;

    public DependencyRelation(String source, String target) {
        this.source = source;
        this.target = target;
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }
}
