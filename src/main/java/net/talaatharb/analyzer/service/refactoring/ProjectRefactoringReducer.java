package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;

public interface ProjectRefactoringReducer {
    boolean supports(RefactoringActionType actionType);

    RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException;
}
