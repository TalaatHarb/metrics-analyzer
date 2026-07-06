package net.talaatharb.analyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePackageStaticAnalyzerSupportTest {

    @Test
    void shouldDetectMavenBuildToolWhenPomExists(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        ServicePackageStaticAnalyzerSupport.BuildTool tool =
                ServicePackageStaticAnalyzerSupport.detectBuildTool(tempDir);

        assertEquals(ServicePackageStaticAnalyzerSupport.BuildTool.MAVEN, tool);
    }

    @Test
    void shouldDetectGradleBuildToolWhenGradleFilesExist(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");

        ServicePackageStaticAnalyzerSupport.BuildTool tool =
                ServicePackageStaticAnalyzerSupport.detectBuildTool(tempDir);

        assertEquals(ServicePackageStaticAnalyzerSupport.BuildTool.GRADLE, tool);
    }

    @Test
    void shouldPreferMavenWhenBothPomAndGradleExist(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");

        ServicePackageStaticAnalyzerSupport.BuildTool tool =
                ServicePackageStaticAnalyzerSupport.detectBuildTool(tempDir);

        assertEquals(ServicePackageStaticAnalyzerSupport.BuildTool.MAVEN, tool);
    }

    @Test
    void shouldBuildGradleCommandsAndReportPaths(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(tempDir.resolve("gradlew"), "");
        Files.writeString(tempDir.resolve("gradlew.bat"), "");

        List<String> pmdCommand = ServicePackageStaticAnalyzerSupport.getPmdCommand(tempDir);
        List<String> checkstyleCommand = ServicePackageStaticAnalyzerSupport.getCheckstyleCommand(tempDir);
        List<String> spotbugsCommand = ServicePackageStaticAnalyzerSupport.getSpotbugsCommand(tempDir);

        String commandPath = pmdCommand.get(0);
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (windows) {
            assertTrue(commandPath.endsWith("gradlew.bat"));
        } else {
            assertTrue(commandPath.endsWith("gradlew"));
        }
        assertEquals("pmdMain", pmdCommand.get(1));
        assertEquals("checkstyleMain", checkstyleCommand.get(1));
        assertEquals("spotbugsMain", spotbugsCommand.get(1));

        assertEquals(
                tempDir.resolve("build").resolve("reports").resolve("pmd").resolve("main.xml"),
                ServicePackageStaticAnalyzerSupport.getPmdReportPath(tempDir)
        );
        assertEquals(
                tempDir.resolve("build").resolve("reports").resolve("checkstyle").resolve("main.xml"),
                ServicePackageStaticAnalyzerSupport.getCheckstyleReportPath(tempDir)
        );
        assertEquals(
                tempDir.resolve("build").resolve("reports").resolve("spotbugs").resolve("main.xml"),
                ServicePackageStaticAnalyzerSupport.getSpotbugsReportPath(tempDir)
        );
    }

    @Test
    void shouldFindMultiModuleMavenReportFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        Path moduleA = tempDir.resolve("module-a");
        Path moduleB = tempDir.resolve("module-b");
        Files.createDirectories(moduleA.resolve("target"));
        Files.createDirectories(moduleB.resolve("target"));
        Files.writeString(moduleA.resolve("target").resolve("pmd.xml"), "<pmd/>");
        Files.writeString(moduleB.resolve("target").resolve("pmd.xml"), "<pmd/>");

        List<Path> reports = ServicePackageStaticAnalyzerSupport.getPmdReportPaths(tempDir);

        assertEquals(2, reports.size());
        assertTrue(reports.contains(moduleA.resolve("target").resolve("pmd.xml")));
        assertTrue(reports.contains(moduleB.resolve("target").resolve("pmd.xml")));
    }

    @Test
    void shouldFindMultiModuleGradleReportFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");

        Path moduleA = tempDir.resolve("module-a");
        Path moduleB = tempDir.resolve("module-b");
        Files.createDirectories(moduleA.resolve("build").resolve("reports").resolve("spotbugs"));
        Files.createDirectories(moduleB.resolve("build").resolve("reports").resolve("spotbugs"));
        Files.writeString(moduleA.resolve("build").resolve("reports").resolve("spotbugs").resolve("main.xml"), "<BugCollection/>");
        Files.writeString(moduleB.resolve("build").resolve("reports").resolve("spotbugs").resolve("main.xml"), "<BugCollection/>");

        List<Path> reports = ServicePackageStaticAnalyzerSupport.getSpotbugsReportPaths(tempDir);

        assertEquals(2, reports.size());
        assertTrue(reports.contains(moduleA.resolve("build").resolve("reports").resolve("spotbugs").resolve("main.xml")));
        assertTrue(reports.contains(moduleB.resolve("build").resolve("reports").resolve("spotbugs").resolve("main.xml")));
    }
}
