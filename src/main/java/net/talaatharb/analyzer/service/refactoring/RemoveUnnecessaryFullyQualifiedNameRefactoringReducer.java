package net.talaatharb.analyzer.service.refactoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces an unnecessary fully qualified type name with its simple class name
 * when that type is already in scope in the same file.
 */
public class RemoveUnnecessaryFullyQualifiedNameRefactoringReducer implements ProjectRefactoringReducer {

    private static final Pattern PACKAGE_DECL = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;\\s*$");
    private static final Pattern IMPORT_DECL = Pattern.compile("^\\s*import\\s+([\\w.]+)\\s*;\\s*$");
    private static final Pattern TOP_LEVEL_TYPE_DECL = Pattern.compile(
            "^\\s*(?:public\\s+)?(?:class|interface|enum|record)\\s+(\\w+)\\b");

    @Override
    public boolean supports(RefactoringActionType actionType) {
        return RefactoringActionType.REMOVE_UNNECESSARY_FULLY_QUALIFIED_NAME == actionType;
    }

    @Override
    public RefactoringResult reduce(ProjectRefactoringState state, RefactoringAction action) throws IOException {
        Path targetFile = action.getTargetFile();
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            return RefactoringResult.noChange("Target file not found.");
        }

        String qualifier = action.getAttributes().get("qualifier");
        String className = action.getAttributes().get("className");
        if (qualifier == null || qualifier.isBlank() || className == null || className.isBlank()) {
            return RefactoringResult.noChange("Missing qualifier/className attributes.");
        }

        String fqcn = qualifier + "." + className;
        List<String> lines = Files.readAllLines(targetFile, StandardCharsets.UTF_8);
        if (!isTypeInScope(lines, qualifier, className, fqcn)) {
            return RefactoringResult.noChange(
                    "Type '" + fqcn + "' is not in scope via imports/current package/current file.");
        }

        int targetIndex = action.getLineNumber() - 1;
        int updatedCount = 0;

        if (targetIndex >= 0 && targetIndex < lines.size()) {
            String updated = replaceFqcn(lines.get(targetIndex), fqcn, className);
            if (!updated.equals(lines.get(targetIndex))) {
                lines.set(targetIndex, updated);
                updatedCount++;
            }
        } else {
            for (int i = 0; i < lines.size(); i++) {
                String updated = replaceFqcn(lines.get(i), fqcn, className);
                if (!updated.equals(lines.get(i))) {
                    lines.set(i, updated);
                    updatedCount++;
                }
            }
        }

        if (updatedCount == 0) {
            return RefactoringResult.noChange(
                    "Fully qualified name '" + fqcn + "' not found on target line.");
        }

        Files.write(targetFile, lines, StandardCharsets.UTF_8);
        return RefactoringResult.modified(
                "Removed unnecessary qualifier '" + qualifier + "' for '" + className + "'.",
                Collections.singletonList(targetFile));
    }

    private boolean isTypeInScope(List<String> lines, String qualifier, String className, String fqcn) {
        String currentPackage = "";
        for (String line : lines) {
            Matcher pkg = PACKAGE_DECL.matcher(line);
            if (pkg.matches()) {
                currentPackage = pkg.group(1);
                break;
            }
        }

        if (qualifier.equals(currentPackage)) {
            return true;
        }

        String exactImport = fqcn;
        String wildcardImport = qualifier + ".*";
        for (String line : lines) {
            Matcher imp = IMPORT_DECL.matcher(line);
            if (imp.matches()) {
                String imported = imp.group(1);
                if (exactImport.equals(imported) || wildcardImport.equals(imported)) {
                    return true;
                }
            }
        }

        // Current-file type declaration
        for (String line : lines) {
            Matcher type = TOP_LEVEL_TYPE_DECL.matcher(line);
            if (type.matches() && className.equals(type.group(1))) {
                return true;
            }
        }

        return false;
    }

    private String replaceFqcn(String line, String fqcn, String className) {
        if (line == null || line.isEmpty() || !line.contains(fqcn)) {
            return line;
        }
        Pattern fqcnPattern = Pattern.compile("(?<![\\w$])" + Pattern.quote(fqcn) + "(?![\\w$])");
        return fqcnPattern.matcher(line).replaceAll(className);
    }
}

