package net.talaatharb.analyzer.ui;

import net.talaatharb.analyzer.model.ClassMetrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ClassMetricsCsvExporter {
    private ClassMetricsCsvExporter() {
    }

    public static void export(
            Path targetFile,
            List<ClassMetrics> metrics
    ) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile must not be null");
        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                targetFile,
                buildCsvContent(metrics),
                StandardCharsets.UTF_8
        );
    }

    static String buildCsvContent(List<ClassMetrics> metrics) {
        List<ClassMetrics> safeMetrics = metrics == null ? Collections.emptyList() : metrics;
        StringBuilder csv = new StringBuilder();
        csv.append("class,package,LOC,methods,fields,coupling,LCOM,CC,WMC,RFC,maintainability_index,debt_score")
                .append(System.lineSeparator());
        for (ClassMetrics metric : safeMetrics) {
            if (metric == null) {
                continue;
            }
            double debtScore = calculateDebtScore(metric);
            csv.append(csvCell(metric.getClassName())).append(',')
                    .append(csvCell(metric.getPackageName())).append(',')
                    .append(csvCell(Integer.toString(metric.getLinesOfCode()))).append(',')
                    .append(csvCell(Integer.toString(metric.getMethodCount()))).append(',')
                    .append(csvCell(Integer.toString(metric.getFieldCount()))).append(',')
                    .append(csvCell(Integer.toString(metric.getEfferentCoupling()))).append(',')
                    .append(csvCell(formatDouble(metric.getLcom()))).append(',')
                    .append(csvCell(Integer.toString(metric.getCyclomaticComplexity()))).append(',')
                    .append(csvCell(Integer.toString(metric.getWeightedMethodsPerClass()))).append(',')
                    .append(csvCell(Integer.toString(metric.getResponseForClass()))).append(',')
                    .append(csvCell(formatDouble(metric.getMaintainabilityIndex()))).append(',')
                    .append(csvCell(formatDouble(debtScore)))
                    .append(System.lineSeparator());
        }
        return csv.toString();
    }

    private static double calculateDebtScore(ClassMetrics cm) {
        double cc = cm.getCyclomaticComplexity();
        double mi = Math.min(100.0, Math.max(0.0, cm.getMaintainabilityIndex()));
        double coupling = cm.getEfferentCoupling();
        return cc * (1.0 - mi / 100.0) * (1.0 + coupling / 10.0);
    }

    private static String formatDouble(double value) {
        if (!Double.isFinite(value)) {
            return "";
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private static String csvCell(String value) {
        String safeValue = value == null ? "" : value;
        boolean requiresQuotes = safeValue.contains(",")
                || safeValue.contains("\"")
                || safeValue.contains("\n")
                || safeValue.contains("\r");
        String escaped = safeValue.replace("\"", "\"\"");
        return requiresQuotes ? "\"" + escaped + "\"" : escaped;
    }
}
