package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SimplifyBooleanReturnRefactoringReducer implements ProjectRefactoringReducer {
    private static final Pattern TRUE_FALSE_WITH_BRACES = Pattern.compile(
            "(?s)(?<indent>^[ \\t]*)if\\s*\\((?<cond>[^\\n\\r)]*)\\)\\s*\\{\\s*return\\s+true\\s*;\\s*}\\s*else\\s*\\{\\s*return\\s+false\\s*;\\s*}",
            Pattern.MULTILINE);
    private static final Pattern FALSE_TRUE_WITH_BRACES = Pattern.compile(
            "(?s)(?<indent>^[ \\t]*)if\\s*\\((?<cond>[^\\n\\r)]*)\\)\\s*\\{\\s*return\\s+false\\s*;\\s*}\\s*else\\s*\\{\\s*return\\s+true\\s*;\\s*}",
            Pattern.MULTILINE);
    private static final Pattern TRUE_FALSE_INLINE = Pattern.compile(
            "(?s)(?<indent>^[ \\t]*)if\\s*\\((?<cond>[^\\n\\r)]*)\\)\\s*return\\s+true\\s*;\\s*else\\s*return\\s+false\\s*;",
            Pattern.MULTILINE);
    private static final Pattern FALSE_TRUE_INLINE = Pattern.compile(
            "(?s)(?<indent>^[ \\t]*)if\\s*\\((?<cond>[^\\n\\r)]*)\\)\\s*return\\s+false\\s*;\\s*else\\s*return\\s+true\\s*;",
            Pattern.MULTILINE);

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.SIMPLIFY_BOOLEAN_RETURN == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        List<String> lines = Files.readAllLines(targetFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return RefactoringResult.noChange("Target file is empty.");
        }

        int hintedLine = action.getLineNumber();
        int center = hintedLine > 0 ? hintedLine - 1 : 0;
        int start = Math.max(0, center - 8);
        int end = Math.min(lines.size() - 1, center + 8);
        String chunk = String.join("\n", lines.subList(start, end + 1));

        String simplified = simplifyChunk(chunk);
        if (simplified == null || simplified.equals(chunk)) {
            return RefactoringResult.noChange(
                    "No simplify-boolean-return pattern found near line " + hintedLine + ".");
        }

        List<String> updated = new ArrayList<>();
        updated.addAll(lines.subList(0, start));
        Collections.addAll(updated, simplified.split("\n", -1));
        updated.addAll(lines.subList(end + 1, lines.size()));

        Files.write(targetFile, updated, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Simplified boolean return conditional near line " + hintedLine + ".",
                Collections.singletonList(targetFile));
    }

    private String simplifyChunk(String chunk) {
        String out = replaceOnce(chunk, TRUE_FALSE_WITH_BRACES, false);
        if (out != null) {
            return out;
        }
        out = replaceOnce(chunk, FALSE_TRUE_WITH_BRACES, true);
        if (out != null) {
            return out;
        }
        out = replaceOnce(chunk, TRUE_FALSE_INLINE, false);
        if (out != null) {
            return out;
        }
        return replaceOnce(chunk, FALSE_TRUE_INLINE, true);
    }

    private String replaceOnce(String input, Pattern pattern, boolean negateCondition) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return null;
        }

        String indent = matcher.group("indent");
        String condition = matcher.group("cond").trim();
        String replacement = negateCondition
                ? indent + "return !(" + condition + ");"
                : indent + "return " + condition + ";";
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }
}
