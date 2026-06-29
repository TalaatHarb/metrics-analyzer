package com.example.analyzer.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class AnalysisResult {
    private final Path analyzedPath;
    private final List<ClassMetrics> classMetrics;
    private final List<DependencyRelation> classCouplings;
    private final List<DependencyRelation> packageCouplings;

    public AnalysisResult(
            Path analyzedPath,
            List<ClassMetrics> classMetrics,
            List<DependencyRelation> classCouplings,
            List<DependencyRelation> packageCouplings
    ) {
        this.analyzedPath = analyzedPath;
        this.classMetrics = Collections.unmodifiableList(new ArrayList<>(classMetrics));
        this.classCouplings = Collections.unmodifiableList(new ArrayList<>(classCouplings));
        this.packageCouplings = Collections.unmodifiableList(new ArrayList<>(packageCouplings));
    }

    public Path getAnalyzedPath() {
        return analyzedPath;
    }

    public List<ClassMetrics> getClassMetrics() {
        return classMetrics;
    }

    public List<DependencyRelation> getClassCouplings() {
        return classCouplings;
    }

    public List<DependencyRelation> getPackageCouplings() {
        return packageCouplings;
    }

    public int getClassCount() {
        return classMetrics.size();
    }

    public int getTotalMethods() {
        return classMetrics.stream().mapToInt(ClassMetrics::getMethodCount).sum();
    }

    public int getTotalFields() {
        return classMetrics.stream().mapToInt(ClassMetrics::getFieldCount).sum();
    }

    public int getTotalLinesOfCode() {
        return classMetrics.stream().mapToInt(ClassMetrics::getLinesOfCode).sum();
    }

    public double getAverageLcom() {
        return classMetrics.stream().mapToDouble(ClassMetrics::getLcom).average().orElse(0.0);
    }

    public double getAverageCoupling() {
        return classMetrics.stream().mapToDouble(ClassMetrics::getEfferentCoupling).average().orElse(0.0);
    }

    public double getAverageComplexity() {
        return classMetrics.stream().mapToDouble(ClassMetrics::getCyclomaticComplexity).average().orElse(0.0);
    }

    public double getAverageMaintainability() {
        return classMetrics.stream().mapToDouble(ClassMetrics::getMaintainabilityIndex).average().orElse(0.0);
    }

    public String buildSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyzed: ").append(analyzedPath).append('\n');
        sb.append("Classes: ").append(getClassCount()).append('\n');
        sb.append("Methods: ").append(getTotalMethods()).append('\n');
        sb.append("Fields: ").append(getTotalFields()).append('\n');
        sb.append("LOC: ").append(getTotalLinesOfCode()).append('\n');
        sb.append("Avg LCOM: ").append(format(getAverageLcom())).append(" (0=high cohesion)\n");
        sb.append("Avg Coupling: ").append(format(getAverageCoupling())).append('\n');
        sb.append("Avg Cyclomatic Complexity: ").append(format(getAverageComplexity())).append('\n');
        sb.append("Avg Maintainability Index: ").append(format(getAverageMaintainability())).append("/100\n");
        sb.append("Class Couplings: ").append(classCouplings.size()).append('\n');
        sb.append("Package Couplings: ").append(packageCouplings.size()).append('\n');
        return sb.toString();
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
