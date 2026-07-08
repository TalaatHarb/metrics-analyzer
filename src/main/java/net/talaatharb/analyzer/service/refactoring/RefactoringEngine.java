package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RefactoringEngine {
    private final List<ProjectRefactoringReducer> reducers;

    public RefactoringEngine(List<ProjectRefactoringReducer> reducers) {
        this.reducers = Collections.unmodifiableList(new ArrayList<>(reducers));
    }

    public static RefactoringEngine createDefault() {
        return new RefactoringEngine(List.of(
                new TodoFixmeRefactoringReducer(),
                new RemoveUselessParenthesesRefactoringReducer(),
                new RemoveUnnecessaryImportRefactoringReducer(),
                new RemoveUnnecessaryReturnRefactoringReducer(),
                new RenameSymbolRefactoringReducer(),
                new RemoveStarImportRefactoringReducer(),
                new RemoveUnnecessaryFullyQualifiedNameRefactoringReducer(),
                new ExtractConstantRefactoringReducer(),
                new ExtractMethodRefactoringReducer(),
                new InlineConstantRefactoringReducer(),
                new InlineVariableRefactoringReducer(),
                new InlineMethodRefactoringReducer()
        ));
    }

    public RefactoringResult apply(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        if (action == null || action.getType() == null) {
            return RefactoringResult.noChange("Action is not defined.");
        }

        for (ProjectRefactoringReducer reducer : reducers) {
            if (reducer.supports(action.getType())) {
                return reducer.reduce(state, action);
            }
        }

        return RefactoringResult.noChange("No reducer supports action type: " + action.getType());
    }
}
