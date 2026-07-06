package net.talaatharb.analyzer.service.refactoring;

import net.talaatharb.analyzer.model.StaticIssue;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RefactoringActionFactory {
    private static final Pattern BACKTICK_EXPRESSION = Pattern.compile("`([^`]+)`");
    private static final Pattern SINGLE_QUOTED_CLASS = Pattern.compile("'([\\w$.]+)'");
    private static final Pattern QUALIFIER_AND_CLASS = Pattern.compile("qualifier\\s+'([\\w.]+)'\\s*:\\s*'([\\w$]+)'");
    private static final Pattern MAGIC_NUMBER_LITERAL = Pattern.compile("'([^']+)'\\s+is\\s+a\\s+magic\\s+number");
    // Matches "import foo.bar.*" or "'foo.bar'" style package mentions
    private static final Pattern STAR_IMPORT_PACKAGE = Pattern.compile("import\\s+([\\w.]+)\\s*\\.\\s*\\*");
    private static final Pattern SINGLE_QUOTED_PACKAGE = Pattern.compile("'([\\w.]+)\\.\\*'");

    private RefactoringActionFactory() {
    }

    public static Optional<RefactoringAction> fromIssue(StaticIssue issue) {
        if (issue == null || issue.getFile() == null) {
            return Optional.empty();
        }

        if ("BASIC_TODO_FIXME".equals(issue.getRuleId())) {
            return Optional.of(new RefactoringAction(
                    RefactoringActionType.NORMALIZE_TODO_FIXME_MARKER,
                    issue.getFile().toAbsolutePath().normalize(),
                    issue.getLineNumber(),
                    Collections.emptyMap()
            ));
        }

        if (isUselessParenthesesIssue(issue)) {
            String expression = extractBacktickExpression(issue.getDescription());
            if (expression != null) {
                Map<String, String> attrs = new HashMap<>();
                attrs.put("expression", expression);
                return Optional.of(new RefactoringAction(
                        RefactoringActionType.REMOVE_USELESS_PARENTHESES,
                        issue.getFile().toAbsolutePath().normalize(),
                        issue.getLineNumber(),
                        attrs
                ));
            }
        }

        if (isUnnecessaryImportIssue(issue)) {
            String importClass = extractSingleQuotedClass(issue.getDescription());
            if (importClass != null) {
                Map<String, String> attrs = new HashMap<>();
                attrs.put("importClass", importClass);
                return Optional.of(new RefactoringAction(
                        RefactoringActionType.REMOVE_UNNECESSARY_IMPORT,
                        issue.getFile().toAbsolutePath().normalize(),
                        issue.getLineNumber(),
                        attrs
                ));
            }
        }

        if (isUnnecessaryReturnIssue(issue)) {
            return Optional.of(new RefactoringAction(
                    RefactoringActionType.REMOVE_UNNECESSARY_RETURN,
                    issue.getFile().toAbsolutePath().normalize(),
                    issue.getLineNumber(),
                    Collections.emptyMap()
            ));
        }

        if (isStarImportIssue(issue)) {
            String pkg = extractStarImportPackage(issue);
            if (pkg != null) {
                Map<String, String> attrs = new HashMap<>();
                attrs.put("importPackage", pkg);
                return Optional.of(new RefactoringAction(
                        RefactoringActionType.REMOVE_STAR_IMPORT,
                        issue.getFile().toAbsolutePath().normalize(),
                        issue.getLineNumber(),
                        attrs
                ));
            }
        }

        if (isUnnecessaryFullyQualifiedNameIssue(issue)) {
            Map<String, String> parts = extractQualifierAndClass(issue.getDescription());
            if (parts != null) {
                return Optional.of(new RefactoringAction(
                        RefactoringActionType.REMOVE_UNNECESSARY_FULLY_QUALIFIED_NAME,
                        issue.getFile().toAbsolutePath().normalize(),
                        issue.getLineNumber(),
                        parts
                ));
            }
        }

        if (isMagicNumberIssue(issue)) {
            String literal = extractMagicNumberLiteral(issue);
            if (literal != null) {
                Map<String, String> attrs = new HashMap<>();
                attrs.put("literal", literal);
                return Optional.of(new RefactoringAction(
                        RefactoringActionType.EXTRACT_CONSTANT,
                        issue.getFile().toAbsolutePath().normalize(),
                        issue.getLineNumber(),
                        attrs
                ));
            }
        }

        return Optional.empty();
    }

    private static boolean isUselessParenthesesIssue(StaticIssue issue) {
        return "UselessParentheses".equals(issue.getRuleId())
                || (issue.getDescription() != null && issue.getDescription().contains("[UselessParentheses]"));
    }

    private static boolean isUnnecessaryImportIssue(StaticIssue issue) {
        String ruleId = issue.getRuleId();
        String desc = issue.getDescription();
        return "UnnecessaryImport".equals(ruleId)
                || "UnusedImports".equals(ruleId)
                || (desc != null && (desc.contains("[UnnecessaryImport]") || desc.contains("[UnusedImports]")));
    }

    private static boolean isUnnecessaryReturnIssue(StaticIssue issue) {
        String ruleId = issue.getRuleId();
        String desc = issue.getDescription();
        return "UnnecessaryReturn".equals(ruleId)
                || (desc != null && desc.contains("[UnnecessaryReturn]"));
    }

    private static boolean isStarImportIssue(StaticIssue issue) {
        String ruleId = issue.getRuleId();
        String desc = issue.getDescription();
        return "AvoidStarImport".equals(ruleId)
                || (desc != null && desc.contains("[AvoidStarImport]"));
    }

    private static boolean isUnnecessaryFullyQualifiedNameIssue(StaticIssue issue) {
        String ruleId = issue.getRuleId();
        String desc = issue.getDescription();
        return "UnnecessaryFullyQualifiedName".equals(ruleId)
                || (desc != null && desc.contains("[UnnecessaryFullyQualifiedName]"));
    }

    private static boolean isMagicNumberIssue(StaticIssue issue) {
        String ruleId = issue.getRuleId();
        String desc = issue.getDescription();
        return "MagicNumber".equals(ruleId)
                || (desc != null && desc.contains("[MagicNumber]"));
    }

    /**
     * Extracts the package name from a star-import issue description.
     * Looks for {@code import foo.bar.*} in the message or {@code 'foo.bar.*'} pattern.
     */
    private static String extractStarImportPackage(StaticIssue issue) {
        String desc = issue.getDescription();
        if (desc == null) {
            return null;
        }
        Matcher m = SINGLE_QUOTED_PACKAGE.matcher(desc);
        if (m.find()) {
            return m.group(1);
        }
        m = STAR_IMPORT_PACKAGE.matcher(desc);
        if (m.find()) {
            return m.group(1);
        }
        // Try reading the actual source line as fallback
        Path file = issue.getFile();
        int lineNum = issue.getLineNumber();
        if (file != null && lineNum > 0 && java.nio.file.Files.isRegularFile(file)) {
            try {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(file);
                if (lineNum - 1 < lines.size()) {
                    Matcher lm = STAR_IMPORT_PACKAGE.matcher(lines.get(lineNum - 1));
                    if (lm.find()) {
                        return lm.group(1);
                    }
                }
            } catch (java.io.IOException e) {
                // best effort
            }
        }
        return null;
    }

    private static String extractBacktickExpression(String description) {
        if (description == null) {
            return null;
        }
        Matcher matcher = BACKTICK_EXPRESSION.matcher(description);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractSingleQuotedClass(String description) {
        if (description == null) {
            return null;
        }
        Matcher matcher = SINGLE_QUOTED_CLASS.matcher(description);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Map<String, String> extractQualifierAndClass(String description) {
        if (description == null) {
            return null;
        }
        Matcher matcher = QUALIFIER_AND_CLASS.matcher(description);
        if (!matcher.find()) {
            return null;
        }
        String qualifier = matcher.group(1);
        String className = matcher.group(2);
        if (qualifier == null || qualifier.isBlank() || className == null || className.isBlank()) {
            return null;
        }
        Map<String, String> attrs = new HashMap<>();
        attrs.put("qualifier", qualifier);
        attrs.put("className", className);
        return attrs;
    }

    private static String extractMagicNumberLiteral(StaticIssue issue) {
        String description = issue.getDescription();
        if (description != null) {
            Matcher matcher = MAGIC_NUMBER_LITERAL.matcher(description);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        Path file = issue.getFile();
        int lineNum = issue.getLineNumber();
        if (file != null && lineNum > 0 && java.nio.file.Files.isRegularFile(file)) {
            try {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(file);
                if (lineNum - 1 < lines.size()) {
                    String line = lines.get(lineNum - 1);
                    Matcher numeric = Pattern.compile("(?<![\\w$])[-+]?\\d+(?:\\.\\d+)?(?:[fFdDlL])?(?![\\w$])").matcher(line);
                    if (numeric.find()) {
                        return numeric.group();
                    }
                }
            } catch (java.io.IOException e) {
                // best effort
            }
        }

        return null;
    }
}
