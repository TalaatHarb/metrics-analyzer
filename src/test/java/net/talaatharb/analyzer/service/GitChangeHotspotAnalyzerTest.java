package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitChangeHotspotAnalyzerTest {

    @Test
    void shouldReturnEmptyForInvalidRoot() {
        GitChangeHotspotAnalyzer analyzer = new GitChangeHotspotAnalyzer();
        assertTrue(analyzer.analyzeProject(null).isEmpty());
        assertTrue(analyzer.analyzeProject(Path.of("missing-root")).isEmpty());
    }

    @Test
    void shouldRankFilesByChangeFrequency(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);
        Path alpha = tempDir.resolve("alpha.txt");
        Path beta = tempDir.resolve("beta.txt");

        writeAndCommit(tempDir, alpha, "a1", "add alpha");
        writeAndCommit(tempDir, beta, "b1", "add beta");
        writeAndCommit(tempDir, alpha, "a2", "update alpha");
        writeAndCommit(tempDir, alpha, "a3", "update alpha again");
        writeAndCommit(tempDir, beta, "b2", "update beta");

        GitChangeHotspotAnalyzer analyzer = new GitChangeHotspotAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeProject(tempDir);

        assertEquals("Git Change Hotspots", analyzer.getName());
        assertEquals(2, issues.size());
        assertEquals(alpha.toAbsolutePath().normalize(), issues.get(0).getFile().toAbsolutePath().normalize());
        assertTrue(issues.get(0).getDescription().contains("changed 3 time(s)"));
        assertEquals(beta.toAbsolutePath().normalize(), issues.get(1).getFile().toAbsolutePath().normalize());
        assertTrue(issues.get(1).getDescription().contains("changed 2 time(s)"));
    }

    @Test
    void shouldRespectConfiguredLimit(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);
        Path alpha = tempDir.resolve("alpha.txt");
        Path beta = tempDir.resolve("beta.txt");
        Path gamma = tempDir.resolve("gamma.txt");

        writeAndCommit(tempDir, alpha, "a1", "add alpha");
        writeAndCommit(tempDir, beta, "b1", "add beta");
        writeAndCommit(tempDir, gamma, "c1", "add gamma");

        GitChangeHotspotAnalyzer analyzer = new GitChangeHotspotAnalyzer(2);
        List<StaticIssue> issues = analyzer.analyzeProject(tempDir);

        assertEquals(2, issues.size());
    }

    @Test
    void shouldCarryFrequencyAcrossRenames(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);
        Path oldName = tempDir.resolve("legacy.txt");
        Path newName = tempDir.resolve("modern.txt");

        writeAndCommit(tempDir, oldName, "v1", "add legacy");
        run(tempDir, "git", "mv", "legacy.txt", "modern.txt");
        run(tempDir, "git", "commit", "-m", "rename legacy to modern");
        writeAndCommit(tempDir, newName, "v2", "update modern");

        GitChangeHotspotAnalyzer analyzer = new GitChangeHotspotAnalyzer();
        List<StaticIssue> issues = analyzer.analyzeProject(tempDir);

        assertEquals(1, issues.size());
        assertEquals(newName.toAbsolutePath().normalize(), issues.get(0).getFile().toAbsolutePath().normalize());
        assertTrue(issues.get(0).getDescription().contains("changed 3 time(s)"));
    }

    private static void initGitRepo(Path root) throws Exception {
        run(root, "git", "init");
        run(root, "git", "config", "user.name", "Test User");
        run(root, "git", "config", "user.email", "test@example.com");
    }

    private static void writeAndCommit(Path root, Path file, String content, String message) throws Exception {
        Files.writeString(file, content);
        run(root, "git", "add", file.getFileName().toString());
        run(root, "git", "commit", "-m", message);
    }

    private static void run(Path root, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile()).start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("Command failed: " + String.join(" ", command));
        }
    }
}
