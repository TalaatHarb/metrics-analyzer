package net.talaatharb.analyzer.service.refactoring;

import net.talaatharb.analyzer.model.StaticIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefactoringEngineTest {

    @Test
    void shouldCreateTodoFixmeActionFromIssue(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "// TODO update");

        StaticIssue issue = new StaticIssue(
                file, 1, "Unresolved TODO or FIXME comment", "Info",
                "technical-debt", "BASIC_TODO_FIXME", "Basic Analyzer",
                0.9, "safe", "Convert TODO/FIXME marker to NOTE or resolve the item.",
                "small", List.of("technical-debt"), "open"
        );

        Optional<RefactoringAction> action = RefactoringActionFactory.fromIssue(issue);
        assertTrue(action.isPresent());
        assertEquals(RefactoringActionType.NORMALIZE_TODO_FIXME_MARKER, action.get().getType());
        assertEquals(file.toAbsolutePath().normalize(), action.get().getTargetFile());
    }

    @Test
    void shouldApplyTodoFixmeReducerViaEngine(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Sample {",
                "  // FIXME clean this up",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.NORMALIZE_TODO_FIXME_MARKER, file, 2, null);

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertTrue(updated.contains("NOTE clean this up"));
        assertFalse(updated.contains("FIXME"));
    }

    // ---- UselessParentheses ----

    @Test
    void shouldCreateUselessParenthesesActionFromPmdIssueViaRuleId(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Calc.java");
        Files.writeString(file, "double n = (raw * 100.0) / 171.0;");

        StaticIssue issue = new StaticIssue(
                file, 1,
                "[UselessParentheses] Useless parentheses around `raw * 100.0`.",
                "Warning",
                "code-style", "UselessParentheses", "PMD Analyzer (Maven)",
                0.8, "safe", "Remove useless parentheses around the expression.",
                "small", List.of("pmd"), "open"
        );

        Optional<RefactoringAction> action = RefactoringActionFactory.fromIssue(issue);
        assertTrue(action.isPresent());
        assertEquals(RefactoringActionType.REMOVE_USELESS_PARENTHESES, action.get().getType());
        assertEquals("raw * 100.0", action.get().getAttributes().get("expression"));
    }

    @Test
    void shouldCreateUselessParenthesesActionFromDescriptionAlone(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Calc.java");
        Files.writeString(file, "double n = (raw * 100.0) / 171.0;");

        // ruleId intentionally blank (old PMD issues without rich metadata)
        StaticIssue issue = new StaticIssue(
                file, 1,
                "[UselessParentheses] Useless parentheses around `raw * 100.0`.",
                "Warning"
        );

        Optional<RefactoringAction> action = RefactoringActionFactory.fromIssue(issue);
        assertTrue(action.isPresent());
        assertEquals(RefactoringActionType.REMOVE_USELESS_PARENTHESES, action.get().getType());
        assertEquals("raw * 100.0", action.get().getAttributes().get("expression"));
    }

    @Test
    void shouldRemoveUselessParenthesesViaEngine(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Calc.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Calc {",
                "    double normalized = (raw * 100.0) / 171.0;",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_USELESS_PARENTHESES,
                file, 2,
                Map.of("expression", "raw * 100.0")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertTrue(updated.contains("raw * 100.0 / 171.0"));
        assertFalse(updated.contains("(raw * 100.0)"));
        assertFalse(result.getModifiedFiles().isEmpty());
    }

    @Test
    void shouldReturnNoChangeWhenExpressionNotOnLine(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Calc.java");
        Files.writeString(file, "double n = x + y;");

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_USELESS_PARENTHESES,
                file, 1,
                Map.of("expression", "raw * 100.0")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        assertFalse(result.isModified());
    }

    @Test
    void shouldReturnNoChangeWhenNoReducerMatchesActionType(@TempDir Path tempDir) throws Exception {
        RefactoringEngine engine = RefactoringEngine.createDefault();
        // Null action type has no reducer
        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), null);
        assertFalse(result.isModified());
    }

    // ---- UnnecessaryImport ----

    @Test
    void shouldCreateRemoveImportActionFromIssueWithRuleId(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "package foo;",
                "import java.util.ArrayList;",
                "class Foo {}"
        ));

        StaticIssue issue = new StaticIssue(
                file, 2,
                "[UnnecessaryImport] Unused import 'java.util.ArrayList'",
                "Warning",
                "code-style", "UnnecessaryImport", "PMD Analyzer (Maven)",
                0.8, "safe", "Remove the unused import statement.",
                "small", List.of("pmd"), "open"
        );

        Optional<RefactoringAction> action = RefactoringActionFactory.fromIssue(issue);
        assertTrue(action.isPresent());
        assertEquals(RefactoringActionType.REMOVE_UNNECESSARY_IMPORT, action.get().getType());
        assertEquals("java.util.ArrayList", action.get().getAttributes().get("importClass"));
        assertEquals(file.toAbsolutePath().normalize(), action.get().getTargetFile());
        assertEquals(2, action.get().getLineNumber());
    }

    @Test
    void shouldCreateRemoveImportActionFromDescriptionAlone(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, "import java.util.ArrayList;");

        // Old-style issue without ruleId
        StaticIssue issue = new StaticIssue(
                file, 1,
                "[UnnecessaryImport] Unused import 'java.util.ArrayList'",
                "Warning"
        );

        Optional<RefactoringAction> action = RefactoringActionFactory.fromIssue(issue);
        assertTrue(action.isPresent());
        assertEquals(RefactoringActionType.REMOVE_UNNECESSARY_IMPORT, action.get().getType());
        assertEquals("java.util.ArrayList", action.get().getAttributes().get("importClass"));
    }

    @Test
    void shouldAlsoMatchLegacyUnusedImportsRuleName(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Bar.java");
        Files.writeString(file, "import java.util.HashMap;");

        StaticIssue issue = new StaticIssue(
                file, 1,
                "[UnusedImports] Unused import 'java.util.HashMap'",
                "Warning",
                "code-style", "UnusedImports", "PMD Analyzer (Maven)",
                0.8, "safe", "Remove the unused import statement.",
                "small", List.of("pmd"), "open"
        );

        Optional<RefactoringAction> action = RefactoringActionFactory.fromIssue(issue);
        assertTrue(action.isPresent());
        assertEquals(RefactoringActionType.REMOVE_UNNECESSARY_IMPORT, action.get().getType());
        assertEquals("java.util.HashMap", action.get().getAttributes().get("importClass"));
    }

    @Test
    void shouldRemoveUnnecessaryImportViaEngine(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Support.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "package net.example;",
                "",
                "import java.io.File;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "",
                "class Support {",
                "    List<String> items;",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_UNNECESSARY_IMPORT,
                file, 4,
                Map.of("importClass", "java.util.ArrayList")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertFalse(updated.contains("import java.util.ArrayList;"));
        assertTrue(updated.contains("import java.util.List;"));
        assertTrue(updated.contains("import java.io.File;"));
        assertFalse(result.getModifiedFiles().isEmpty());
    }

    @Test
    void shouldFallBackToScanWhenLineHintIsWrong(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Support.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "import java.util.ArrayList;",
                "class Support {}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        // Hint points to line 2 which is wrong — reducer should still find and remove it
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_UNNECESSARY_IMPORT,
                file, 2,
                Map.of("importClass", "java.util.ArrayList")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertFalse(updated.contains("import java.util.ArrayList;"));
    }

    @Test
    void shouldReturnNoChangeWhenImportNotInFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, "class Foo {}");

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_UNNECESSARY_IMPORT,
                file, 1,
                Map.of("importClass", "java.util.ArrayList")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        assertFalse(result.isModified());
    }

    // ---- UnnecessaryReturn ----

    @Test
    void shouldCreateRemoveReturnActionFromIssue(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "void doSomething() {",
                "    log();",
                "    return;",
                "}"
        ));

        StaticIssue issue = new StaticIssue(
                file, 3,
                "[UnnecessaryReturn] Avoid unnecessary return statements",
                "Warning",
                "code-style", "UnnecessaryReturn", "PMD Analyzer (Maven)",
                0.8, "safe", "Remove the unnecessary return statement.",
                "small", List.of("pmd"), "open"
        );

        Optional<RefactoringAction> action = RefactoringActionFactory.fromIssue(issue);
        assertTrue(action.isPresent());
        assertEquals(RefactoringActionType.REMOVE_UNNECESSARY_RETURN, action.get().getType());
        assertEquals(3, action.get().getLineNumber());
    }

    @Test
    void shouldRemoveUnnecessaryReturnViaEngine(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Foo {",
                "    void doSomething() {",
                "        doWork();",
                "        return;",
                "    }",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_UNNECESSARY_RETURN,
                file, 4, null
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertFalse(updated.contains("return;"));
        assertTrue(updated.contains("doWork();"));
    }

    @Test
    void shouldReturnNoChangeWhenNoReturnStatementFound(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Foo {",
                "    void doSomething() {}",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_UNNECESSARY_RETURN,
                file, 2, null
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        assertFalse(result.isModified());
    }

    // ---- RenameSymbol ----

    @Test
    void shouldRenameSymbolInFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("MyClass.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class MyClass {",
                "    int counter = 0;",
                "    void increment() { counter++; }",
                "    int getCounter() { return counter; }",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.RENAME_SYMBOL,
                file, -1,
                Map.of("oldName", "counter", "newName", "count")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertFalse(updated.contains("counter"));
        assertTrue(updated.contains("count"));
        assertTrue(updated.contains("getCounter")); // method name not affected — contains 'Counter' not 'counter'
    }

    @Test
    void shouldNotPartiallyRenameWhenNameIsSubstring(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Calc.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "int count = 0;",
                "int counter = 1;",
                "count++;"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.RENAME_SYMBOL,
                file, -1,
                Map.of("oldName", "count", "newName", "total")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertTrue(updated.contains("int total = 0;"));
        assertTrue(updated.contains("int counter = 1;")); // 'counter' untouched
        assertTrue(updated.contains("total++;")); // 'count++' renamed
    }

    @Test
    void shouldReturnNoChangeWhenOldNameNotInFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, "class Foo {}");

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.RENAME_SYMBOL,
                file, -1,
                Map.of("oldName", "missing", "newName", "found")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        assertFalse(result.isModified());
    }

    @Test
    void shouldRejectInvalidNewName(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, "int count = 0;");

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.RENAME_SYMBOL,
                file, -1,
                Map.of("oldName", "count", "newName", "123invalid")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        assertFalse(result.isModified());
    }

    // ---- RemoveStarImport ----

    @Test
    void shouldDetectStarImportIssueFromCheckstyle(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "import java.util.*;",
                "class Foo {}"
        ));

        StaticIssue issue = new StaticIssue(
                file, 1,
                "[AvoidStarImport] Using the '.*' form of import should be avoided - java.util.*.",
                "Warning",
                "code-style", "AvoidStarImport", "Checkstyle Analyzer (Maven)",
                0.85, "safe", "Replace the star import with explicit class imports.",
                "small", List.of("checkstyle"), "New"
        );

        Optional<RefactoringAction> action = RefactoringActionFactory.fromIssue(issue);
        assertTrue(action.isPresent());
        assertEquals(RefactoringActionType.REMOVE_STAR_IMPORT, action.get().getType());
    }

    @Test
    void shouldRemoveStarImportViaEngine(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "package com.example;",
                "",
                "import java.util.*;",
                "import java.io.File;",
                "",
                "class Foo {}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_STAR_IMPORT,
                file, 3,
                Map.of("importPackage", "java.util")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertFalse(updated.contains("import java.util.*;"));
        assertTrue(updated.contains("import java.io.File;"));
    }

    @Test
    void shouldReturnNoChangeWhenStarImportNotInFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, "class Foo {}");

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_STAR_IMPORT,
                file, 1,
                Map.of("importPackage", "java.util")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        assertFalse(result.isModified());
    }

    // ---- UnnecessaryFullyQualifiedName ----

    @Test
    void shouldCreateActionFromUnnecessaryFullyQualifiedNameIssue(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("FileExplorerTab.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "import javafx.geometry.Pos;",
                "class FileExplorerTab {",
                "  void f() { controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT); }",
                "}"
        ));

        StaticIssue issue = new StaticIssue(
                file, 3,
                "[UnnecessaryFullyQualifiedName] Unnecessary qualifier 'javafx.geometry': 'Pos' is already in scope because it is imported in this file",
                "Warning",
                "general", "UnnecessaryFullyQualifiedName", "PMD Analyzer (Maven)",
                0.8, "safe", "Replace fully qualified name with class name.", "small",
                List.of("pmd"), "New"
        );

        Optional<RefactoringAction> action = RefactoringActionFactory.fromIssue(issue);
        assertTrue(action.isPresent());
        assertEquals(RefactoringActionType.REMOVE_UNNECESSARY_FULLY_QUALIFIED_NAME, action.get().getType());
        assertEquals("javafx.geometry", action.get().getAttributes().get("qualifier"));
        assertEquals("Pos", action.get().getAttributes().get("className"));
    }

    @Test
    void shouldRemoveUnnecessaryFullyQualifiedNameWhenImported(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("FileExplorerTab.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "import javafx.geometry.Pos;",
                "class FileExplorerTab {",
                "  void f() { controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT); }",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_UNNECESSARY_FULLY_QUALIFIED_NAME,
                file, 3,
                Map.of("qualifier", "javafx.geometry", "className", "Pos")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertTrue(updated.contains("controls.setAlignment(Pos.CENTER_LEFT);"));
        assertFalse(updated.contains("setAlignment(javafx.geometry.Pos"));
    }

    @Test
    void shouldRemoveUnnecessaryFullyQualifiedNameWhenCurrentPackageMatches(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "package com.example;",
                "class Foo {",
                "  com.example.Bar value;",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_UNNECESSARY_FULLY_QUALIFIED_NAME,
                file, 3,
                Map.of("qualifier", "com.example", "className", "Bar")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertTrue(updated.contains("Bar value;"));
        assertFalse(updated.contains("com.example.Bar"));
    }

    @Test
    void shouldReturnNoChangeWhenFullyQualifiedTypeIsNotInScope(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "package com.example;",
                "class Foo {",
                "  void f() { com.other.Bar value = null; }",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.REMOVE_UNNECESSARY_FULLY_QUALIFIED_NAME,
                file, 3,
                Map.of("qualifier", "com.other", "className", "Bar")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        assertFalse(result.isModified());
    }

    // ---- ExtractConstant ----

    @Test
    void shouldCreateExtractConstantActionFromMagicNumberIssue(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Foo {",
                "    int timeout = 30;",
                "}"
        ));

        StaticIssue issue = new StaticIssue(
                file, 2,
                "[MagicNumber] '30' is a magic number.",
                "Warning",
                "maintainability", "MagicNumber", "Checkstyle Analyzer (Maven)",
                0.85, "review", "Extract as constant.", "small",
                List.of("checkstyle"), "New"
        );

        Optional<RefactoringAction> action = RefactoringActionFactory.fromIssue(issue);
        assertTrue(action.isPresent());
        assertEquals(RefactoringActionType.EXTRACT_CONSTANT, action.get().getType());
        assertEquals("30", action.get().getAttributes().get("literal"));
    }

    @Test
    void shouldExtractConstantAndReplaceUsage(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Foo {",
                "    int timeout = 30;",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.EXTRACT_CONSTANT,
                file, 2,
                Map.of("literal", "30", "constantName", "DEFAULT_TIMEOUT")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertTrue(updated.contains("private static final int DEFAULT_TIMEOUT = 30;"));
        assertTrue(updated.contains("int timeout = DEFAULT_TIMEOUT;"));
    }

    @Test
    void shouldReturnNoChangeForUnsupportedLiteral(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Foo {",
                "    Object x = new Object();",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.EXTRACT_CONSTANT,
                file, 2,
                Map.of("literal", "new Object()", "constantName", "OBJ")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        assertFalse(result.isModified());
    }

    @Test
    void shouldAutoSuffixConstantNameWhenNameAlreadyExists(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Foo {",
                "    private static final int DEFAULT_TIMEOUT = 20;",
                "    int timeout = 30;",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.EXTRACT_CONSTANT,
                file, 3,
                Map.of("literal", "30", "constantName", "DEFAULT_TIMEOUT")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertTrue(updated.contains("private static final int DEFAULT_TIMEOUT_2 = 30;"));
        assertTrue(updated.contains("int timeout = DEFAULT_TIMEOUT_2;"));
    }

    @Test
    void shouldReplaceAllOccurrencesWhenRequested(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Foo {",
                "    int a = 30;",
                "    int b = 30;",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.EXTRACT_CONSTANT,
                file, 2,
                Map.of("literal", "30", "constantName", "DEFAULT_TIMEOUT", "replaceAllOccurrences", "true")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertTrue(updated.contains("int a = DEFAULT_TIMEOUT;"));
        assertTrue(updated.contains("int b = DEFAULT_TIMEOUT;"));
    }

    @Test
    void shouldSkipCommentsAndAnnotationsDuringReplacement(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Foo {",
                "    @SuppressWarnings(\"30\")",
                "    // 30 should remain in comment",
                "    int a = 30;",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.EXTRACT_CONSTANT,
                file, 4,
                Map.of("literal", "30", "constantName", "DEFAULT_TIMEOUT", "replaceAllOccurrences", "true")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertTrue(updated.contains("@SuppressWarnings(\"30\")"));
        assertTrue(updated.contains("// 30 should remain in comment"));
        assertTrue(updated.contains("int a = DEFAULT_TIMEOUT;"));
    }

    @Test
    void shouldReplaceCodeBeforeInlineCommentAndPreserveComment(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Foo.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Foo {",
                "    int a = 30; // keep 30 in comment",
                "}"
        ));

        RefactoringEngine engine = RefactoringEngine.createDefault();
        RefactoringAction action = new RefactoringAction(
                RefactoringActionType.EXTRACT_CONSTANT,
                file, 2,
                Map.of("literal", "30", "constantName", "DEFAULT_TIMEOUT", "replaceAllOccurrences", "true")
        );

        RefactoringResult result = engine.apply(new ProjectRefactoringState(tempDir), action);
        String updated = Files.readString(file);

        assertTrue(result.isModified());
        assertTrue(updated.contains("int a = DEFAULT_TIMEOUT; // keep 30 in comment"));
    }
}
