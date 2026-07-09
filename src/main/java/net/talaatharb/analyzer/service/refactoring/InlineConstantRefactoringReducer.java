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

/**
 * Inlines a named constant by replacing its usages with the literal value.
 *
 * <p>Required attributes:
 * <ul>
 *   <li>{@code constantName} — the name of the constant to inline.</li>
 * </ul>
 *
 * <p>Optional attributes:
 * <ul>
 *   <li>{@code replaceAllOccurrences} — {@code "true"} replaces every usage in the file and removes
 *       the constant declaration; {@code "false"} (default) replaces only the single usage at the
 *       hinted line number and leaves the declaration intact.</li>
 * </ul>
 */
public class InlineConstantRefactoringReducer implements ProjectRefactoringReducer {

    private static final Pattern CONSTANT_DECL = Pattern.compile(
            "^(\\s*)(?:(?:public|private|protected)\\s+)?(?:static\\s+)?final\\s+[\\w<>\\[\\]?,\\s]+\\s+(\\w+)\\s*=\\s*(.+?)\\s*;\\s*$");

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.INLINE_CONSTANT == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        String constantName = action.getAttributes().get("constantName");
        if (constantName == null || constantName.isBlank()) {
            return RefactoringResult.noChange("No constantName provided in action attributes.");
        }

        boolean replaceAllOccurrences = Boolean.parseBoolean(
                action.getAttributes().getOrDefault("replaceAllOccurrences", "false"));

        List<String> lines = new ArrayList<>(Files.readAllLines(targetFile, StandardCharsets.UTF_8));

        // Find the constant declaration to extract its literal value
        int declarationIndex = -1;
        String literalValue = null;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = CONSTANT_DECL.matcher(lines.get(i));
            if (m.matches() && constantName.equals(m.group(2))) {
                declarationIndex = i;
                literalValue = m.group(3).trim();
                break;
            }
        }

        if (declarationIndex < 0 || literalValue == null) {
            return RefactoringResult.noChange(
                    "Constant declaration for '" + constantName + "' not found in file.");
        }

        Pattern usagePattern = Pattern.compile("(?<![\\w$])" + Pattern.quote(constantName) + "(?![\\w$])");
        int replacementCount = 0;

        if (replaceAllOccurrences) {
            for (int i = 0; i < lines.size(); i++) {
                if (i == declarationIndex) {
                    continue;
                }
                String original = lines.get(i);
                String replaced = usagePattern.matcher(original).replaceAll(Matcher.quoteReplacement(literalValue));
                if (!original.equals(replaced)) {
                    lines.set(i, replaced);
                    replacementCount++;
                }
            }
            // Remove the declaration after all usages are replaced
            lines.remove(declarationIndex);
            // Collapse any blank line left behind
            if (declarationIndex < lines.size() && lines.get(declarationIndex).isBlank()
                    && declarationIndex > 0 && lines.get(declarationIndex - 1).isBlank()) {
                lines.remove(declarationIndex);
            }
        } else {
            int hintedIndex = action.getLineNumber() - 1;
            if (hintedIndex >= 0 && hintedIndex < lines.size() && hintedIndex != declarationIndex) {
                String original = lines.get(hintedIndex);
                Matcher m = usagePattern.matcher(original);
                if (m.find()) {
                    lines.set(hintedIndex, m.replaceFirst(Matcher.quoteReplacement(literalValue)));
                    replacementCount = 1;
                }
            }
            if (replacementCount == 0) {
                // Fall back: replace the first usage found anywhere in the file (excluding the declaration)
                for (int i = 0; i < lines.size(); i++) {
                    if (i == declarationIndex) {
                        continue;
                    }
                    String original = lines.get(i);
                    Matcher m = usagePattern.matcher(original);
                    if (m.find()) {
                        lines.set(i, m.replaceFirst(Matcher.quoteReplacement(literalValue)));
                        replacementCount = 1;
                        break;
                    }
                }
            }
        }

        if (replacementCount == 0) {
            return RefactoringResult.noChange(
                    "No usages of constant '" + constantName + "' found in file.");
        }

        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Inlined constant '" + constantName + "' with value '" + literalValue + "'"
                        + " (" + replacementCount + " occurrence(s) replaced"
                        + (replaceAllOccurrences ? ", declaration removed" : "") + ").",
                Collections.singletonList(targetFile));
    }
}
