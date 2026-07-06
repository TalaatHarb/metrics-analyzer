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
 * Extracts a literal into a class-level constant and replaces usage with the constant name.
 */
public class ExtractConstantRefactoringReducer implements ProjectRefactoringReducer {
    private static final Pattern CLASS_DECL = Pattern.compile("^\\s*(?:public\\s+)?(?:class|interface|enum|record)\\s+\\w+.*$");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z\\d_$]*");
    private static final Pattern MODIFIER_CONST_DECL = Pattern.compile(
            "^\\s*(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\],?]+\\s+([A-Za-z_$][A-Za-z\\d_$]*)\\b.*$");

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.EXTRACT_CONSTANT == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        String literal = action.getAttributes().get("literal");
        if (literal == null || literal.isBlank()) {
            return RefactoringResult.noChange("No literal provided in action attributes.");
        }
        String type = inferType(literal);
        if (type == null) {
            return RefactoringResult.noChange("Literal '" + literal + "' is not supported for extraction.");
        }

        String desiredName = action.getAttributes().get("constantName");
        String constantName = normalizeConstantName(desiredName, literal);
        if (!IDENTIFIER.matcher(constantName).matches()) {
            return RefactoringResult.noChange("Invalid constant name '" + constantName + "'.");
        }

        boolean replaceAllOccurrences = Boolean.parseBoolean(
                action.getAttributes().getOrDefault("replaceAllOccurrences", "false"));

        List<String> lines = new ArrayList<>(Files.readAllLines(targetFile, StandardCharsets.UTF_8));
        int classIndex = findClassDeclarationLine(lines);
        if (classIndex < 0) {
            return RefactoringResult.noChange("Could not find class declaration to insert constant.");
        }

        constantName = resolveUniqueConstantName(lines, constantName);

        int replacementCount = replaceLiteralUsages(lines, literal, constantName, action.getLineNumber(), replaceAllOccurrences);
        if (replacementCount == 0) {
            return RefactoringResult.noChange("Literal '" + literal + "' not found in file.");
        }

        if (!hasExistingConstant(lines, constantName, literal)) {
            String indent = extractIndent(lines.get(classIndex)) + "    ";
            String declaration = indent + "private static final " + type + " " + constantName + " = " + literal + ";";
            lines.add(classIndex + 1, declaration);
            if (classIndex + 2 < lines.size() && !lines.get(classIndex + 2).isBlank()) {
                lines.add(classIndex + 2, "");
            }
        }

        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Extracted literal '" + literal + "' to constant '" + constantName + "'"
                        + " (" + replacementCount + " occurrence(s) replaced).",
                Collections.singletonList(targetFile));
    }

    private int findClassDeclarationLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (CLASS_DECL.matcher(lines.get(i)).matches()) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasExistingConstant(List<String> lines, String constantName, String literal) {
        String marker = constantName + " = " + literal + ";";
        for (String line : lines) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private int replaceLiteralUsages(
            List<String> lines,
            String literal,
            String constantName,
            int lineNumberHint,
            boolean replaceAllOccurrences
    ) {
        int replacementCount = 0;
        int hintedIndex = lineNumberHint - 1;

        if (!replaceAllOccurrences && hintedIndex >= 0 && hintedIndex < lines.size()) {
            String line = lines.get(hintedIndex);
            LineReplacement hinted = replaceLiteralInLine(line, literal, constantName, false);
            if (hinted.replacements > 0) {
                lines.set(hintedIndex, hinted.line);
                return hinted.replacements;
            }
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LineReplacement replaced = replaceLiteralInLine(line, literal, constantName, replaceAllOccurrences);
            if (replaced.replacements > 0) {
                lines.set(i, replaced.line);
                replacementCount += replaced.replacements;
                if (!replaceAllOccurrences) {
                    break;
                }
            }
        }
        return replacementCount;
    }

    private LineReplacement replaceLiteralInLine(
            String line,
            String literal,
            String replacement,
            boolean replaceAllOccurrences
    ) {
        if (line == null || line.isBlank()) {
            return LineReplacement.none(line);
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("@")) {
            return LineReplacement.none(line); // avoid annotations
        }
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("*/")) {
            return LineReplacement.none(line); // comment lines
        }
        if (line.contains("/*") || line.contains("*/")) {
            return LineReplacement.none(line); // keep conservative behavior for block comments
        }

        int inlineCommentIdx = findInlineCommentStart(line);
        String codePart = inlineCommentIdx >= 0 ? line.substring(0, inlineCommentIdx) : line;
        String commentPart = inlineCommentIdx >= 0 ? line.substring(inlineCommentIdx) : "";

        Pattern literalPattern = Pattern.compile("(?<![\\w$])" + Pattern.quote(literal) + "(?![\\w$])");
        Matcher matcher = literalPattern.matcher(codePart);
        if (!matcher.find()) {
            return LineReplacement.none(line);
        }

        if (!replaceAllOccurrences) {
            String replacedCode = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
            return new LineReplacement(replacedCode + commentPart, 1);
        }

        int count = 1;
        while (matcher.find()) {
            count++;
        }
        String replacedCode = literalPattern.matcher(codePart).replaceAll(Matcher.quoteReplacement(replacement));
        return new LineReplacement(replacedCode + commentPart, count);
    }

    private int findInlineCommentStart(String line) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < line.length() - 1; i++) {
            char ch = line.charAt(i);
            char next = line.charAt(i + 1);

            if (escaped) {
                escaped = false;
                continue;
            }
            if ((inSingleQuote || inDoubleQuote) && ch == '\\') {
                escaped = true;
                continue;
            }
            if (!inDoubleQuote && ch == '\'') {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (!inSingleQuote && ch == '"') {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote && ch == '/' && next == '/') {
                return i;
            }
        }
        return -1;
    }

    private static final class LineReplacement {
        private final String line;
        private final int replacements;

        private LineReplacement(String line, int replacements) {
            this.line = line;
            this.replacements = replacements;
        }

        private static LineReplacement none(String line) {
            return new LineReplacement(line, 0);
        }
    }

    private String extractIndent(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }

    private String normalizeConstantName(String desiredName, String literal) {
        if (desiredName != null && !desiredName.isBlank()) {
            return desiredName.trim();
        }

        String base = literal;
        if (base.startsWith("\"") && base.endsWith("\"") && base.length() >= 2) {
            base = base.substring(1, base.length() - 1);
        }
        base = base.replaceAll("[^A-Za-z\\d]+", "_").replaceAll("^_+|_+$", "");
        if (base.isBlank()) {
            base = "EXTRACTED_CONSTANT";
        } else {
            base = "MAGIC_" + base.toUpperCase();
        }
        if (!Character.isJavaIdentifierStart(base.charAt(0))) {
            base = "CONST_" + base;
        }
        return base;
    }

    private String resolveUniqueConstantName(List<String> lines, String baseName) {
        if (!hasAnyConstantNamed(lines, baseName)) {
            return baseName;
        }
        int suffix = 2;
        while (hasAnyConstantNamed(lines, baseName + "_" + suffix)) {
            suffix++;
        }
        return baseName + "_" + suffix;
    }

    private boolean hasAnyConstantNamed(List<String> lines, String name) {
        for (String line : lines) {
            Matcher matcher = MODIFIER_CONST_DECL.matcher(line);
            if (matcher.matches() && name.equals(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private String inferType(String literal) {
        String trimmed = literal.trim();
        if (trimmed.matches("\"(?:[^\"\\\\]|\\\\.)*\"")) {
            return "String";
        }
        if (trimmed.matches("'(?:[^'\\\\]|\\\\.)'")) {
            return "char";
        }
        if ("true".equals(trimmed) || "false".equals(trimmed)) {
            return "boolean";
        }
        if (trimmed.matches("[-+]?\\d+[lL]")) {
            return "long";
        }
        if (trimmed.matches("[-+]?(?:\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][-+]?\\d+)?[fF]")) {
            return "float";
        }
        if (trimmed.matches("[-+]?(?:\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][-+]?\\d+)?[dD]?")) {
            return "double";
        }
        if (trimmed.matches("[-+]?\\d+")) {
            return "int";
        }
        return null;
    }
}
