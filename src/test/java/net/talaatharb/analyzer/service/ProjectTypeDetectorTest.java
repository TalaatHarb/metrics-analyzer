package net.talaatharb.analyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTypeDetectorTest {

    @Test
    void shouldDetectMavenProject(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        assertEquals(ProjectType.JAVA_MAVEN, ProjectTypeDetector.detect(tempDir));
        assertTrue(ProjectTypeDetector.supportsJavaBuildAnalysis(tempDir));
    }

    @Test
    void shouldDetectGradleProject(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");

        assertEquals(ProjectType.JAVA_GRADLE, ProjectTypeDetector.detect(tempDir));
        assertTrue(ProjectTypeDetector.supportsJavaBuildAnalysis(tempDir));
    }

    @Test
    void shouldDetectJarFile(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("app.jar");
        Files.writeString(jar, "");

        assertEquals(ProjectType.JVM_JAR, ProjectTypeDetector.detect(jar));
        assertFalse(ProjectTypeDetector.supportsJavaBuildAnalysis(jar));
    }

    @Test
    void shouldDetectNodeProject(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("package.json"), "{ }");

        assertEquals(ProjectType.JAVASCRIPT_TYPESCRIPT, ProjectTypeDetector.detect(tempDir));
        assertFalse(ProjectTypeDetector.supportsJavaBuildAnalysis(tempDir));
    }

    @Test
    void shouldDetectPlainJavaSourceProject(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src").resolve("main").resolve("java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App {}");

        assertEquals(ProjectType.JAVA_SOURCE, ProjectTypeDetector.detect(tempDir));
        assertFalse(ProjectTypeDetector.supportsJavaBuildAnalysis(tempDir));
    }
}
