package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ServicePackageStaticAnalyzerSupport {
    enum BuildTool {
        MAVEN,
        GRADLE
    }

    static final String SERVICE_PACKAGE_FRAGMENT = "/net/talaatharb/analyzer/service/";
    static final String SERVICE_PACKAGE_SOURCE_RELATIVE_PATH = "src/main/java/net/talaatharb/analyzer/service";

    private ServicePackageStaticAnalyzerSupport() {
    }

    static String getMavenCommand() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
    }

    static BuildTool detectBuildTool(Path rootPath) {
        ProjectType projectType = ProjectTypeDetector.detect(rootPath);
        if (projectType == ProjectType.JAVA_MAVEN) {
            return BuildTool.MAVEN;
        }
        return projectType == ProjectType.JAVA_GRADLE
                ? BuildTool.GRADLE
                : BuildTool.MAVEN;
    }

    static boolean supportsJavaBuildAnalysis(Path rootPath) {
        return ProjectTypeDetector.supportsJavaBuildAnalysis(rootPath);
    }

    static StaticIssue unsupportedJavaBuildProjectIssue(String analyzerName, Path rootPath) {
        ProjectType projectType = ProjectTypeDetector.detect(rootPath);
        return new StaticIssue(
                rootPath,
                0,
                analyzerName + " currently supports Maven or Gradle Java projects only. Detected project type: "
                        + projectType + ".",
                "Warning"
        );
    }

    static String getGradleCommand(Path rootPath) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (windows) {
            Path wrapper = rootPath.resolve("gradlew.bat");
            return Files.isRegularFile(wrapper) ? wrapper.toAbsolutePath().toString() : "gradle";
        }
        Path wrapper = rootPath.resolve("gradlew");
        return Files.isRegularFile(wrapper) ? wrapper.toAbsolutePath().toString() : "gradle";
    }

    static List<String> getPmdCommand(Path rootPath) {
        if (detectBuildTool(rootPath) == BuildTool.GRADLE) {
            return List.of(getGradleCommand(rootPath), "pmdMain", "--continue");
        }
        return List.of(
                getMavenCommand(),
                "org.apache.maven.plugins:maven-pmd-plugin:3.28.0:pmd",
                "-Dpmd.failOnViolation=false",
                "-Dpmd.skipEmptyReport=false"
        );
    }

    static List<String> getCheckstyleCommand(Path rootPath) {
        if (detectBuildTool(rootPath) == BuildTool.GRADLE) {
            return List.of(getGradleCommand(rootPath), "checkstyleMain", "--continue");
        }
        return List.of(
                getMavenCommand(),
                "org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:checkstyle",
                "-Dcheckstyle.failOnViolation=false"
        );
    }

    static List<String> getSpotbugsCommand(Path rootPath) {
        if (detectBuildTool(rootPath) == BuildTool.GRADLE) {
            return List.of(getGradleCommand(rootPath), "spotbugsMain", "--continue");
        }
        return List.of(
                getMavenCommand(),
                "compile",
                "com.github.spotbugs:spotbugs-maven-plugin:4.10.2.0:spotbugs",
                "-Dspotbugs.failOnError=false"
        );
    }

    static Path getPmdReportPath(Path rootPath) {
        if (detectBuildTool(rootPath) == BuildTool.GRADLE) {
            return rootPath.resolve("build").resolve("reports").resolve("pmd").resolve("main.xml");
        }
        return rootPath.resolve("target").resolve("pmd.xml");
    }

    static Path getCheckstyleReportPath(Path rootPath) {
        if (detectBuildTool(rootPath) == BuildTool.GRADLE) {
            return rootPath.resolve("build").resolve("reports").resolve("checkstyle").resolve("main.xml");
        }
        return rootPath.resolve("target").resolve("checkstyle-result.xml");
    }

    static Path getSpotbugsReportPath(Path rootPath) {
        if (detectBuildTool(rootPath) == BuildTool.GRADLE) {
            return rootPath.resolve("build").resolve("reports").resolve("spotbugs").resolve("main.xml");
        }
        return rootPath.resolve("target").resolve("spotbugsXml.xml");
    }

    static List<Path> getPmdReportPaths(Path rootPath) {
        if (detectBuildTool(rootPath) == BuildTool.GRADLE) {
            return findReportFiles(rootPath, "/build/reports/pmd/main.xml");
        }
        return findReportFiles(rootPath, "/target/pmd.xml");
    }

    static List<Path> getCheckstyleReportPaths(Path rootPath) {
        if (detectBuildTool(rootPath) == BuildTool.GRADLE) {
            return findReportFiles(rootPath, "/build/reports/checkstyle/main.xml");
        }
        return findReportFiles(rootPath, "/target/checkstyle-result.xml");
    }

    static List<Path> getSpotbugsReportPaths(Path rootPath) {
        if (detectBuildTool(rootPath) == BuildTool.GRADLE) {
            return findReportFiles(rootPath, "/build/reports/spotbugs/main.xml");
        }
        return findReportFiles(rootPath, "/target/spotbugsXml.xml");
    }

    private static List<Path> findReportFiles(Path rootPath, String normalizedSuffix) {
        if (rootPath == null || normalizedSuffix == null || normalizedSuffix.isBlank()) {
            return List.of();
        }

        List<Path> result = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(rootPath, 8)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> normalizePath(path.toString()).endsWith(normalizedSuffix))
                    .sorted(Comparator.comparing(path -> normalizePath(path.toString())))
                    .forEach(result::add);
        } catch (IOException ignored) {
            return List.of();
        }
        return result;
    }

    static Path getServicePackagePath(Path rootPath) {
        return rootPath.resolve(SERVICE_PACKAGE_SOURCE_RELATIVE_PATH);
    }

    static boolean isServicePackageFile(Path file) {
        return normalizePath(file.toString()).contains(SERVICE_PACKAGE_FRAGMENT);
    }

    static boolean isServicePackageFile(String filePath) {
        return normalizePath(filePath).contains(SERVICE_PACKAGE_FRAGMENT);
    }

    static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    static Path resolveSourcePath(Path rootPath, String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return rootPath;
        }

        Path rawPath = Path.of(sourcePath);
        if (rawPath.isAbsolute() && Files.isRegularFile(rawPath)) {
            return rawPath.toAbsolutePath().normalize();
        }

        Path mainPath = rootPath.resolve("src").resolve("main").resolve("java").resolve(sourcePath);
        if (Files.isRegularFile(mainPath)) {
            return mainPath.toAbsolutePath().normalize();
        }

        Path testPath = rootPath.resolve("src").resolve("test").resolve("java").resolve(sourcePath);
        if (Files.isRegularFile(testPath)) {
            return testPath.toAbsolutePath().normalize();
        }

        Path direct = rootPath.resolve(sourcePath);
        if (Files.isRegularFile(direct)) {
            return direct.toAbsolutePath().normalize();
        }

        return direct.toAbsolutePath().normalize();
    }

    static ProcessExecution runCommand(Path workingDirectory, String logPrefix, List<String> command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());

        File logFile = File.createTempFile(logPrefix, ".log");
        pb.redirectOutput(logFile);
        pb.redirectError(logFile);

        Process process = pb.start();
        int exitCode = process.waitFor();
        return new ProcessExecution(exitCode, logFile.toPath());
    }

    static String getLogTail(Path logFile, int tailLines) {
        try {
            List<String> lines = Files.readAllLines(logFile);
            int start = Math.max(0, lines.size() - tailLines);
            return String.join(" | ", lines.subList(start, lines.size()));
        } catch (Exception ignored) {
            return "";
        }
    }

    static void deleteFileQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    static final class ProcessExecution {
        private final int exitCode;
        private final Path logFile;

        ProcessExecution(int exitCode, Path logFile) {
            this.exitCode = exitCode;
            this.logFile = logFile;
        }

        int getExitCode() {
            return exitCode;
        }

        Path getLogFile() {
            return logFile;
        }
    }
}
