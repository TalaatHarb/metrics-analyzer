package net.talaatharb.analyzer.service.refactoring;

import java.nio.file.Path;

public final class ProjectRefactoringState {
    private final Path projectRoot;

    public ProjectRefactoringState(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }
}
