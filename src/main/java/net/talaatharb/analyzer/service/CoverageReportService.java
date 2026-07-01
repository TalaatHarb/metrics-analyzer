package net.talaatharb.analyzer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CoverageReportService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CoverageReportService.class);
    private static final String JACOCO_XML = "target/site/jacoco/jacoco.xml";
    private static final String JACOCO_AGGREGATE_XML = "target/site/jacoco-aggregate/jacoco.xml";
    private static final String COBERTURA_XML = "target/site/cobertura/coverage.xml";
    private static final String COBERTURA_FALLBACK_XML = "target/coverage.xml";

    public enum CoverageLineStatus {
        COVERED,
        PARTIAL,
        MISSED
    }

    public static class CoverageData {
        private final Map<Path, Map<Integer, CoverageLineStatus>> linesByFile;
        private final String reportType;
        private final int fileCount;
        private final int lineCount;

        public CoverageData(Map<Path, Map<Integer, CoverageLineStatus>> linesByFile, String reportType, int fileCount, int lineCount) {
            this.linesByFile = linesByFile;
            this.reportType = reportType;
            this.fileCount = fileCount;
            this.lineCount = lineCount;
        }

        public Map<Path, Map<Integer, CoverageLineStatus>> getLinesByFile() {
            return linesByFile;
        }

        public String getReportType() {
            return reportType;
        }

        public int getFileCount() {
            return fileCount;
        }

        public int getLineCount() {
            return lineCount;
        }
    }

    public CoverageData generateAndLoadCoverage(Path rootPath) {
        if (rootPath == null) {
            throw new IllegalArgumentException("Project path is required.");
        }
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Project path does not exist: " + rootPath);
        }

        String generationError = runMavenCoverage(rootPath);
        CoverageData parsed = loadCoverageFromReports(rootPath);
        if (parsed == null) {
            if (generationError != null) {
                throw new IllegalStateException("Coverage generation failed. " + generationError);
            }
            throw new IllegalStateException("No JaCoCo or Cobertura XML report was found after coverage run.");
        }
        return parsed;
    }

    private String runMavenCoverage(Path rootPath) {
        try {
            String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
            ProcessBuilder pb = new ProcessBuilder(
                    mvnCmd,
                    "org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent",
                    "test",
                    "org.jacoco:jacoco-maven-plugin:0.8.12:report",
                    "-DskipTests=false"
            );
            pb.directory(rootPath.toFile());

            File tempLog = File.createTempFile("coverage-maven-log", ".txt");
            pb.redirectOutput(tempLog);
            pb.redirectError(tempLog);

            Process process = pb.start();
            int exitCode = process.waitFor();

            String error = null;
            if (exitCode != 0) {
                List<String> lines = Files.readAllLines(tempLog.toPath());
                int start = Math.max(0, lines.size() - 8);
                String tail = String.join(" | ", lines.subList(start, lines.size()));
                error = "Maven exited with code " + exitCode + ". Log tail: " + tail;
                LOGGER.warn("Coverage generation exited with code {} for {}", exitCode, rootPath);
            }
            tempLog.delete();
            return error;
        } catch (Exception ex) {
            return "Failed to execute Maven coverage goals: " + ex.getMessage();
        }
    }

    private CoverageData loadCoverageFromReports(Path rootPath) {
        Path jacocoXml = rootPath.resolve(JACOCO_XML);
        Path jacocoAggregateXml = rootPath.resolve(JACOCO_AGGREGATE_XML);
        Path coberturaXml = rootPath.resolve(COBERTURA_XML);
        Path coberturaFallbackXml = rootPath.resolve(COBERTURA_FALLBACK_XML);

        if (Files.isRegularFile(jacocoXml)) {
            return parseJaCoCo(rootPath, jacocoXml);
        }
        if (Files.isRegularFile(jacocoAggregateXml)) {
            return parseJaCoCo(rootPath, jacocoAggregateXml);
        }
        if (Files.isRegularFile(coberturaXml)) {
            return parseCobertura(rootPath, coberturaXml);
        }
        if (Files.isRegularFile(coberturaFallbackXml)) {
            return parseCobertura(rootPath, coberturaFallbackXml);
        }
        return null;
    }

    private CoverageData parseJaCoCo(Path rootPath, Path reportPath) {
        Map<Path, Map<Integer, CoverageLineStatus>> linesByFile = new HashMap<>();
        int lineCount = 0;
        try {
            Document doc = parseXml(reportPath);
            NodeList packageNodes = doc.getElementsByTagName("package");
            for (int i = 0; i < packageNodes.getLength(); i++) {
                Element packageElement = (Element) packageNodes.item(i);
                String packageName = packageElement.getAttribute("name");
                NodeList sourceFiles = packageElement.getElementsByTagName("sourcefile");
                for (int j = 0; j < sourceFiles.getLength(); j++) {
                    Element sourceFile = (Element) sourceFiles.item(j);
                    String sourceName = sourceFile.getAttribute("name");
                    Path filePath = resolveReportPath(rootPath, packageName, sourceName);
                    Map<Integer, CoverageLineStatus> fileMap = linesByFile.computeIfAbsent(filePath, ignored -> new HashMap<>());
                    NodeList lineNodes = sourceFile.getElementsByTagName("line");
                    for (int k = 0; k < lineNodes.getLength(); k++) {
                        Element line = (Element) lineNodes.item(k);
                        int lineNumber = Integer.parseInt(line.getAttribute("nr"));
                        int mi = parseIntAttribute(line, "mi");
                        int ci = parseIntAttribute(line, "ci");
                        int mb = parseIntAttribute(line, "mb");
                        int cb = parseIntAttribute(line, "cb");
                        CoverageLineStatus status = toJaCoCoStatus(mi, ci, mb, cb);
                        mergeCoverageStatus(fileMap, lineNumber, status);
                    }
                }
            }

            for (Map<Integer, CoverageLineStatus> lineMap : linesByFile.values()) {
                lineCount += lineMap.size();
            }
            return new CoverageData(makeImmutable(linesByFile), "JaCoCo", linesByFile.size(), lineCount);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse JaCoCo report: " + ex.getMessage(), ex);
        }
    }

    private CoverageData parseCobertura(Path rootPath, Path reportPath) {
        Map<Path, Map<Integer, CoverageLineStatus>> linesByFile = new HashMap<>();
        int lineCount = 0;
        try {
            Document doc = parseXml(reportPath);
            NodeList classNodes = doc.getElementsByTagName("class");
            for (int i = 0; i < classNodes.getLength(); i++) {
                Element classElement = (Element) classNodes.item(i);
                String filename = classElement.getAttribute("filename");
                Path filePath = resolveCoberturaPath(rootPath, filename);
                Map<Integer, CoverageLineStatus> fileMap = linesByFile.computeIfAbsent(filePath, ignored -> new HashMap<>());

                NodeList lineNodes = classElement.getElementsByTagName("line");
                for (int j = 0; j < lineNodes.getLength(); j++) {
                    Element line = (Element) lineNodes.item(j);
                    int lineNumber = Integer.parseInt(line.getAttribute("number"));
                    int hits = parseIntAttribute(line, "hits");
                    String branch = line.getAttribute("branch");
                    String conditionCoverage = line.getAttribute("condition-coverage");
                    CoverageLineStatus status = toCoberturaStatus(hits, branch, conditionCoverage);
                    mergeCoverageStatus(fileMap, lineNumber, status);
                }
            }

            for (Map<Integer, CoverageLineStatus> lineMap : linesByFile.values()) {
                lineCount += lineMap.size();
            }
            return new CoverageData(makeImmutable(linesByFile), "Cobertura", linesByFile.size(), lineCount);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Cobertura report: " + ex.getMessage(), ex);
        }
    }

    private static Document parseXml(Path reportPath) throws Exception {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        dbFactory.setNamespaceAware(false);
        dbFactory.setValidating(false);
        dbFactory.setXIncludeAware(false);
        dbFactory.setExpandEntityReferences(false);
        setFeatureIfSupported(dbFactory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(dbFactory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        setFeatureIfSupported(dbFactory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(dbFactory, "http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        dBuilder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document doc = dBuilder.parse(reportPath.toFile());
        doc.getDocumentElement().normalize();
        return doc;
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory dbFactory, String feature, boolean value) {
        try {
            dbFactory.setFeature(feature, value);
        } catch (Exception ignored) {
            // Some XML parsers do not support all features; continue with best effort.
        }
    }

    private static Path resolveReportPath(Path rootPath, String packageName, String sourceName) {
        String packagePath = packageName == null || packageName.isEmpty() ? "" : packageName.replace('/', File.separatorChar);
        Path relativePath = packagePath.isEmpty() ? Path.of(sourceName) : Path.of(packagePath, sourceName);

        Path mainPath = rootPath.resolve("src").resolve("main").resolve("java").resolve(relativePath);
        if (Files.isRegularFile(mainPath)) {
            return mainPath.toAbsolutePath().normalize();
        }

        Path testPath = rootPath.resolve("src").resolve("test").resolve("java").resolve(relativePath);
        if (Files.isRegularFile(testPath)) {
            return testPath.toAbsolutePath().normalize();
        }

        Path direct = rootPath.resolve(relativePath);
        if (Files.isRegularFile(direct)) {
            return direct.toAbsolutePath().normalize();
        }
        return mainPath.toAbsolutePath().normalize();
    }

    private static Path resolveCoberturaPath(Path rootPath, String filename) {
        if (filename == null || filename.isEmpty()) {
            return rootPath.toAbsolutePath().normalize();
        }

        Path rawPath = Path.of(filename);
        if (rawPath.isAbsolute() && Files.isRegularFile(rawPath)) {
            return rawPath.toAbsolutePath().normalize();
        }

        Path mainPath = rootPath.resolve("src").resolve("main").resolve("java").resolve(rawPath);
        if (Files.isRegularFile(mainPath)) {
            return mainPath.toAbsolutePath().normalize();
        }

        Path testPath = rootPath.resolve("src").resolve("test").resolve("java").resolve(rawPath);
        if (Files.isRegularFile(testPath)) {
            return testPath.toAbsolutePath().normalize();
        }

        Path direct = rootPath.resolve(rawPath);
        return direct.toAbsolutePath().normalize();
    }

    private static CoverageLineStatus toJaCoCoStatus(int mi, int ci, int mb, int cb) {
        boolean hasCovered = ci > 0 || cb > 0;
        boolean hasMissed = mi > 0 || mb > 0;
        if (hasCovered && hasMissed) {
            return CoverageLineStatus.PARTIAL;
        }
        if (hasCovered) {
            return CoverageLineStatus.COVERED;
        }
        return CoverageLineStatus.MISSED;
    }

    private static CoverageLineStatus toCoberturaStatus(int hits, String branch, String conditionCoverage) {
        if (hits <= 0) {
            return CoverageLineStatus.MISSED;
        }

        if ("true".equalsIgnoreCase(branch)) {
            String normalized = conditionCoverage == null ? "" : conditionCoverage.trim().toLowerCase();
            int slashIndex = normalized.indexOf('/');
            int openParenIndex = normalized.indexOf('(');
            if (slashIndex > 0 && openParenIndex > slashIndex) {
                try {
                    int covered = Integer.parseInt(normalized.substring(0, slashIndex).trim());
                    int total = Integer.parseInt(normalized.substring(slashIndex + 1, openParenIndex).trim());
                    if (covered < total) {
                        return CoverageLineStatus.PARTIAL;
                    }
                } catch (NumberFormatException ignored) {
                    // Fallback to covered if format is unexpected.
                }
            }
        }

        return CoverageLineStatus.COVERED;
    }

    private static int parseIntAttribute(Element element, String attrName) {
        String value = element.getAttribute(attrName);
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    private static void mergeCoverageStatus(Map<Integer, CoverageLineStatus> fileMap, int lineNumber, CoverageLineStatus status) {
        CoverageLineStatus existing = fileMap.get(lineNumber);
        if (existing == null) {
            fileMap.put(lineNumber, status);
            return;
        }

        if (existing == status) {
            return;
        }
        fileMap.put(lineNumber, CoverageLineStatus.PARTIAL);
    }

    private static Map<Path, Map<Integer, CoverageLineStatus>> makeImmutable(Map<Path, Map<Integer, CoverageLineStatus>> mutableMap) {
        Map<Path, Map<Integer, CoverageLineStatus>> immutable = new HashMap<>();
        for (Map.Entry<Path, Map<Integer, CoverageLineStatus>> entry : mutableMap.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableMap(new HashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(immutable);
    }
}
