package net.talaatharb.analyzer.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

final class ProjectTypeDetector {
    private ProjectTypeDetector() {
    }

    static ProjectType detect(Path rootPath) {
        if (rootPath == null) {
            return ProjectType.UNKNOWN;
        }

        if (Files.isRegularFile(rootPath)) {
            String fileName = rootPath.getFileName().toString().toLowerCase();
            if (fileName.endsWith(".jar")) {
                return ProjectType.JVM_JAR;
            }
            return ProjectType.UNKNOWN;
        }

        if (Files.isRegularFile(rootPath.resolve("pom.xml"))) {
            return ProjectType.JAVA_MAVEN;
        }
        if (Files.isRegularFile(rootPath.resolve("build.gradle"))
                || Files.isRegularFile(rootPath.resolve("build.gradle.kts"))
                || Files.isRegularFile(rootPath.resolve("settings.gradle"))
                || Files.isRegularFile(rootPath.resolve("settings.gradle.kts"))
                || Files.isRegularFile(rootPath.resolve("gradlew"))
                || Files.isRegularFile(rootPath.resolve("gradlew.bat"))) {
            return ProjectType.JAVA_GRADLE;
        }
        if (Files.isRegularFile(rootPath.resolve("deps.edn"))
                || Files.isRegularFile(rootPath.resolve("project.clj"))) {
            return ProjectType.CLOJURE;
        }
        if (Files.isRegularFile(rootPath.resolve("build.sbt"))) {
            return ProjectType.SCALA;
        }
        if (Files.isRegularFile(rootPath.resolve("package.json"))) {
            return ProjectType.JAVASCRIPT_TYPESCRIPT;
        }
        if (Files.isRegularFile(rootPath.resolve("pyproject.toml"))
                || Files.isRegularFile(rootPath.resolve("requirements.txt"))) {
            return ProjectType.PYTHON;
        }

        Set<String> extensions = collectExtensions(rootPath, 4);
        if (extensions.contains(".java")) {
            return ProjectType.JAVA_SOURCE;
        }
        if (extensions.contains(".kt") || extensions.contains(".kts")) {
            return ProjectType.KOTLIN;
        }
        if (extensions.contains(".scala")) {
            return ProjectType.SCALA;
        }
        if (extensions.contains(".clj")) {
            return ProjectType.CLOJURE;
        }
        if (extensions.contains(".ts") || extensions.contains(".tsx")
                || extensions.contains(".js") || extensions.contains(".jsx")) {
            return ProjectType.JAVASCRIPT_TYPESCRIPT;
        }
        if (extensions.contains(".py")) {
            return ProjectType.PYTHON;
        }

        return ProjectType.UNKNOWN;
    }

    static boolean supportsJavaBuildAnalysis(Path rootPath) {
        ProjectType projectType = detect(rootPath);
        return projectType == ProjectType.JAVA_MAVEN || projectType == ProjectType.JAVA_GRADLE;
    }

    private static Set<String> collectExtensions(Path rootPath, int maxDepth) {
        Set<String> extensions = new java.util.HashSet<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(rootPath, maxDepth)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        int index = fileName.lastIndexOf('.');
                        if (index >= 0) {
                            extensions.add(fileName.substring(index));
                        }
                    });
        } catch (IOException ignored) {
            return Set.of();
        }
        return extensions;
    }
}
