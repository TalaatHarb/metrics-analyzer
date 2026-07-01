package net.talaatharb.analyzer.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ServicePackageStaticAnalyzerSupport {
    static final String SERVICE_PACKAGE_FRAGMENT = "/net/talaatharb/analyzer/service/";
    static final String SERVICE_PACKAGE_SOURCE_RELATIVE_PATH = "src/main/java/net/talaatharb/analyzer/service";

    private ServicePackageStaticAnalyzerSupport() {
    }

    static String getMavenCommand() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
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
