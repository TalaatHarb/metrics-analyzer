package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class GitChangeHotspotAnalyzer implements StaticAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitChangeHotspotAnalyzer.class);
    private static final int DEFAULT_MAX_RESULTS = 300;
    private static final String HOTSPOT_RULE_ID = "GIT_CHANGE_HOTSPOT";

    private final int maxResults;

    public GitChangeHotspotAnalyzer() {
        this(DEFAULT_MAX_RESULTS);
    }

    GitChangeHotspotAnalyzer(int maxResults) {
        this.maxResults = Math.max(1, maxResults);
    }

    @Override
    public String getName() {
        return "Git Change Hotspots";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null || !Files.exists(rootPath)) {
            return issues;
        }

        Path normalizedRoot = rootPath.toAbsolutePath().normalize();
        Path gitRoot = resolveGitRoot(normalizedRoot);
        if (gitRoot == null) {
            return issues;
        }

        Map<Path, Integer> frequency = new HashMap<>();
        List<String> output = runGitLog(gitRoot);
        for (String line : output) {
            String relative = line == null ? "" : line.trim();
            if (relative.isEmpty()) {
                continue;
            }
            Path file = gitRoot.resolve(relative).normalize();
            if (!file.startsWith(gitRoot) || !file.startsWith(normalizedRoot) || !Files.isRegularFile(file)) {
                continue;
            }
            frequency.merge(file, 1, Integer::sum);
        }

        List<Map.Entry<Path, Integer>> ranked = frequency.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Path, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(entry -> entry.getKey().toString()))
                .limit(maxResults)
                .collect(Collectors.toList());

        for (int i = 0; i < ranked.size(); i++) {
            Map.Entry<Path, Integer> entry = ranked.get(i);
            int rank = i + 1;
            issues.add(createIssue(entry.getKey(), entry.getValue(), rank));
        }

        return issues;
    }

    private Path resolveGitRoot(Path workingDir) {
        ProcessBuilder processBuilder = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                .directory(workingDir.toFile());
        try {
            Process process = processBuilder.start();
            String root;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                root = reader.readLine();
            }
            int exit = process.waitFor();
            if (exit != 0 || root == null || root.isBlank()) {
                return null;
            }
            return Path.of(root.trim()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            LOGGER.debug("Could not resolve git root for {}", workingDir, ex);
            return null;
        }
    }

    private List<String> runGitLog(Path gitRoot) {
        List<String> lines = new ArrayList<>();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "git",
                "log",
                "--name-only",
                "--pretty=format:",
                "--no-merges"
        ).directory(gitRoot.toFile());

        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                LOGGER.warn("git log exited with code {} for {}", exit, gitRoot);
            }
        } catch (IOException | InterruptedException ex) {
            LOGGER.warn("Failed to run git log for {}", gitRoot, ex);
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return lines;
    }

    private StaticIssue createIssue(Path file, int changeCount, int rank) {
        String severity = changeCount >= 20 ? "Warning" : "Info";
        return new StaticIssue(
                file,
                1,
                String.format(Locale.US, "Hotspot #%d: changed %d time(s) in git history", rank, changeCount),
                severity,
                "change-frequency",
                HOTSPOT_RULE_ID,
                getName(),
                0.95,
                "none",
                "Review this frequently changed file for refactoring opportunities.",
                "none",
                List.of("git", "hotspot", "change-frequency"),
                "open"
        );
    }

    @Override
    public String toString() {
        return getName();
    }
}
