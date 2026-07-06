package net.talaatharb.analyzer.ui;

import javafx.collections.ObservableList;
import javafx.scene.control.Tab;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import net.talaatharb.analyzer.model.StaticIssue;
import net.talaatharb.analyzer.service.StaticAnalyzer;
import net.talaatharb.analyzer.service.BasicStaticAnalyzer;
import net.talaatharb.analyzer.service.MissingThingsAnalyzer;
import net.talaatharb.analyzer.service.PMDStaticAnalyzer;
import net.talaatharb.analyzer.service.CheckstyleStaticAnalyzer;
import net.talaatharb.analyzer.service.SpotBugsStaticAnalyzer;
import net.talaatharb.analyzer.service.FindBugsStaticAnalyzer;
import net.talaatharb.analyzer.service.FindSecBugsStaticAnalyzer;
import net.talaatharb.analyzer.service.InferStaticAnalyzer;
import net.talaatharb.analyzer.service.SemgrepSastStaticAnalyzer;
import net.talaatharb.analyzer.service.JQAssistantStaticAnalyzer;
import net.talaatharb.analyzer.service.refactoring.ProjectRefactoringState;
import net.talaatharb.analyzer.service.refactoring.RefactoringAction;
import net.talaatharb.analyzer.service.refactoring.RefactoringActionFactory;
import net.talaatharb.analyzer.service.refactoring.RefactoringEngine;
import net.talaatharb.analyzer.service.refactoring.RefactoringResult;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.concurrent.Task;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.IndexRange;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableRow;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.Node;
import javafx.geometry.Pos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.StreamSupport;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.talaatharb.analyzer.service.refactoring.RefactoringActionType;
import net.talaatharb.analyzer.service.CoverageReportService;
import net.talaatharb.analyzer.service.CoverageReportService.CoverageData;
import net.talaatharb.analyzer.service.CoverageReportService.CoverageLineStatus;

public class FileExplorerTab {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileExplorerTab.class);
    private static final Pattern MAVEN_COMPILE_ERROR_PATTERN = Pattern.compile(
            "^\\[ERROR\\]\\s+(.+?\\.java):\\[(\\d+),(\\d+)\\]\\s+(.+)$");
    private static final Pattern MAVEN_COMPILE_DETAIL_PATTERN = Pattern.compile(
            "^\\[ERROR\\]\\s+(symbol|location):\\s+(.+)$");
    private final CodeArea codeArea;
    private final TreeView<File> fileTree;
    private final Label currentFileLabel;
    private final Label breadcrumbsLabel;
    private final Label issueSummaryLabel;
    private final Label buildStatusLabel;
    private final Label compileErrorsSummaryLabel;
    private final ObservableList<CompileErrorRow> compileErrors;
    private final RefactoringEngine refactoringEngine;
    private final CoverageReportService coverageReportService;
    private Path currentFilePath;
    private Map<Path, Map<Integer, CoverageLineStatus>> coverageByFile;
    private Map<Path, CoverageSummary> coverageSummaryByPath;
    private Map<Integer, CoverageLineStatus> currentFileCoverage;
    private Path rootPath;
    private boolean loadingFileContent;
    private boolean currentFileDirty;
    private List<StaticIssue> latestIssues;
    private Set<String> baselineFingerprints;
    private String latestAnalyzerName;
    private boolean showNewIssuesOnly;
    // Last-fix undo state
    private Path lastFixOriginalPath;
    private Path lastFixBackupPath;
    private MenuItem undoFixMenuItemRef;

    public FileExplorerTab() {
        this.coverageReportService = new CoverageReportService();
        this.coverageByFile = Collections.emptyMap();
        this.coverageSummaryByPath = Collections.emptyMap();
        this.currentFileCoverage = Collections.emptyMap();
        this.latestIssues = Collections.emptyList();
        this.baselineFingerprints = Collections.emptySet();
        this.latestAnalyzerName = "";
        this.showNewIssuesOnly = false;
        this.fileTree = createFileTree();
        this.codeArea = createCodeArea();
        this.currentFileLabel = new Label("File: No file selected");
        this.breadcrumbsLabel = new Label("Path: -");
        this.issueSummaryLabel = new Label("Issues: 0");
        this.buildStatusLabel = new Label("Build: Not checked");
        this.compileErrorsSummaryLabel = new Label("Compile errors: 0");
        this.compileErrors = FXCollections.observableArrayList();
        this.refactoringEngine = RefactoringEngine.createDefault();
    }

    public Tab createTab(Path projectPath) {
        this.rootPath = projectPath;
        
        BorderPane content = new BorderPane();
        
        VBox treeContainer = new VBox(fileTree);
        treeContainer.setPadding(new Insets(8));
        treeContainer.setMinWidth(0);
        VBox.setVgrow(fileTree, Priority.ALWAYS);
        treeContainer.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 0 1 0 0;");
        treeContainer.setPrefWidth(250);
        fileTree.setMinWidth(0);
        
        currentFileLabel.setStyle("-fx-font-weight: bold;");
        breadcrumbsLabel.setStyle("-fx-text-fill: #6b7280;");
        VBox codeHeader = new VBox(2, currentFileLabel, breadcrumbsLabel);
        VBox codeContainer = new VBox(8, codeHeader, codeArea);
        codeContainer.setPadding(new Insets(8));
        VBox.setVgrow(codeArea, Priority.ALWAYS);
        
        TableView<StaticIssue> problemsTable = createProblemsTable();
        TableView<CompileErrorRow> compileErrorsTable = createCompileErrorsTable();
        
        ComboBox<StaticAnalyzer> analyzerComboBox = new ComboBox<>();
        List<StaticAnalyzer> allAnalyzers = Arrays.asList(
            new BasicStaticAnalyzer(),
            new MissingThingsAnalyzer(),
            new PMDStaticAnalyzer(),
            new CheckstyleStaticAnalyzer(),
            new SpotBugsStaticAnalyzer(),
            new FindBugsStaticAnalyzer(),
            new FindSecBugsStaticAnalyzer(),
            new InferStaticAnalyzer(),
            new SemgrepSastStaticAnalyzer(),
            new JQAssistantStaticAnalyzer()
        );
        analyzerComboBox.getItems().addAll(allAnalyzers);
        analyzerComboBox.getSelectionModel().selectFirst();
        ComboBox<String> scopeComboBox = new ComboBox<>();
        scopeComboBox.getItems().addAll("Project", "Module/Folder", "Current File");
        scopeComboBox.getSelectionModel().selectFirst();
        
        ProgressIndicator analysisProgress = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        analysisProgress.setPrefSize(16, 16);
        analysisProgress.setVisible(false);
        analysisProgress.setManaged(false);
        ProgressIndicator coverageProgress = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        coverageProgress.setPrefSize(16, 16);
        coverageProgress.setVisible(false);
        coverageProgress.setManaged(false);
        issueSummaryLabel.setStyle("-fx-text-fill: #6b7280;");
        buildStatusLabel.setStyle("-fx-text-fill: #6b7280;");
        compileErrorsSummaryLabel.setStyle("-fx-text-fill: #6b7280;");
        Label coverageStatusLabel = new Label("Coverage: Not loaded");
        coverageStatusLabel.setStyle("-fx-text-fill: #6b7280;");

        MenuButton operationsMenu = new MenuButton("Operations");
        MenuItem scanMenuItem = new MenuItem("Scan for Problems");
        MenuItem setBaselineMenuItem = new MenuItem("Set Baseline From Current Results");
        MenuItem fixSafeIssuesMenuItem = new MenuItem("Fix All Safe Issues (Visible)");
        MenuItem undoFixMenuItem = new MenuItem("Undo Last Fix");
        undoFixMenuItem.setDisable(true);
        this.undoFixMenuItemRef = undoFixMenuItem;
        MenuItem saveFileMenuItem = new MenuItem("Save Current File");
        MenuItem reloadFileMenuItem = new MenuItem("Reload Current File");
        MenuItem generateCoverageMenuItem = new MenuItem("Generate Coverage");
        MenuItem clearCoverageMenuItem = new MenuItem("Clear Coverage Info");
        operationsMenu.getItems().addAll(
                scanMenuItem,
                setBaselineMenuItem,
                fixSafeIssuesMenuItem,
                undoFixMenuItem,
                new SeparatorMenuItem(),
                saveFileMenuItem,
                reloadFileMenuItem,
                new SeparatorMenuItem(),
                generateCoverageMenuItem,
                clearCoverageMenuItem
        );

        scanMenuItem.setOnAction(e -> {
            StaticAnalyzer selectedAnalyzer = analyzerComboBox.getValue();
            if (selectedAnalyzer == null) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Analyzer Required");
                alert.setHeaderText(null);
                alert.setContentText("No analyzer is available for the selected scope.");
                alert.showAndWait();
                return;
            }
            operationsMenu.setDisable(true);
            analysisProgress.setVisible(true);
            analysisProgress.setManaged(true);
            runScopedAnalysis(problemsTable, selectedAnalyzer, scopeComboBox.getValue(), () -> {
                operationsMenu.setDisable(false);
                analysisProgress.setVisible(false);
                analysisProgress.setManaged(false);
            });
        });
        
        saveFileMenuItem.setOnAction(e -> saveCurrentFile());
        reloadFileMenuItem.setOnAction(e -> {
            if (currentFilePath != null && Files.isRegularFile(currentFilePath)) {
                loadFileContent(currentFilePath);
            }
        });
        setBaselineMenuItem.setOnAction(e -> {
            if (latestIssues.isEmpty() || latestAnalyzerName.isBlank()) {
                return;
            }
            saveBaseline(latestAnalyzerName, latestIssues);
            baselineFingerprints = loadBaseline(latestAnalyzerName);
            applyIssueFilter(problemsTable);
        });
        fixSafeIssuesMenuItem.setOnAction(e -> {
            // Bulk fix clears the undo-able single-fix backup
            clearLastFixBackup();
            undoFixMenuItem.setDisable(true);
            applyAllSafeQuickFixes(problemsTable);
        });
        undoFixMenuItem.setOnAction(e -> {
            Path targetToReload = lastFixOriginalPath;
            if (undoLastFix()) {
                undoFixMenuItem.setDisable(true);
                if (currentFilePath != null && targetToReload != null
                        && currentFilePath.equals(targetToReload)) {
                    loadFileContent(currentFilePath);
                }
                clearLastFixBackup();
                runCompileSafetyGate();
            }
        });

        javafx.scene.control.CheckBox showNewOnlyBox = new javafx.scene.control.CheckBox("Show New Issues Only");
        showNewOnlyBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            showNewIssuesOnly = newVal;
            applyIssueFilter(problemsTable);
        });

        generateCoverageMenuItem.setOnAction(e -> {
            operationsMenu.setDisable(true);
            coverageProgress.setVisible(true);
            coverageProgress.setManaged(true);
            runCoverageAnalysis(coverageStatusLabel, () -> {
                operationsMenu.setDisable(false);
                coverageProgress.setVisible(false);
                coverageProgress.setManaged(false);
            });
        });

        clearCoverageMenuItem.setOnAction(e -> clearCoverageInfo(coverageStatusLabel));

        HBox controls = new HBox(
                8,
                new Label("Analyzer:"),
                analyzerComboBox,
                new Label("Scope:"),
                scopeComboBox,
                showNewOnlyBox,
                operationsMenu,
                analysisProgress,
                coverageProgress,
                issueSummaryLabel,
                buildStatusLabel,
                coverageStatusLabel
        );
        controls.setAlignment(Pos.CENTER_LEFT);

        Label compileErrorsHeader = new Label("Compile Errors");
        compileErrorsHeader.setStyle("-fx-font-weight: bold;");
        HBox compileErrorsMeta = new HBox(8, compileErrorsHeader, compileErrorsSummaryLabel);
        compileErrorsMeta.setAlignment(Pos.CENTER_LEFT);
        VBox compileErrorsContainer = new VBox(4, compileErrorsMeta, compileErrorsTable);
        compileErrorsTable.setPrefHeight(140);
        compileErrorsTable.setMinHeight(100);

        VBox problemsContainer = new VBox(8, controls, problemsTable, compileErrorsContainer);
        problemsContainer.setPadding(new Insets(8));
        VBox.setVgrow(problemsTable, Priority.ALWAYS);
        
        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
        splitPane.getItems().addAll(codeContainer, problemsContainer);
        splitPane.setDividerPositions(0.7);

        SplitPane horizontalSplit = new SplitPane();
        horizontalSplit.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        horizontalSplit.getItems().addAll(treeContainer, splitPane);
        horizontalSplit.setDividerPositions(0.22);
        splitPane.setMinWidth(0);

        content.setCenter(horizontalSplit);
        
        // Apply CSS stylesheet for syntax highlighting
        String cssResource = getClass().getResource("/syntax-highlighting.css").toExternalForm();
        content.getStylesheets().add(cssResource);
        
        refreshFileTree(projectPath);
        
        Tab tab = new Tab("File Explorer", content);
        tab.setClosable(false);
        return tab;
    }
    
    private TableView<StaticIssue> createProblemsTable() {
        TableView<StaticIssue> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        
        TableColumn<StaticIssue, String> fileCol = new TableColumn<>("File");
        fileCol.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().getFile().getFileName().toString()));
        
        TableColumn<StaticIssue, Number> lineCol = new TableColumn<>("Line");
        lineCol.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue().getLineNumber()));
        lineCol.setPrefWidth(50);
        lineCol.setMaxWidth(50);
        
        TableColumn<StaticIssue, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().getDescription()));
        descCol.setCellFactory(tc -> {
            TableCell<StaticIssue, String> cell = new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        setText(item);
                    }
                }
            };
            
            cell.setWrapText(true);
            
            ContextMenu contextMenu = new ContextMenu();
            MenuItem copyItem = new MenuItem("Copy Description");
            copyItem.setOnAction(e -> {
                if (cell.getItem() != null) {
                    Clipboard clipboard = Clipboard.getSystemClipboard();
                    ClipboardContent content = new ClipboardContent();
                    content.putString(cell.getItem());
                    clipboard.setContent(content);
                }
            });
            
            MenuItem detailsItem = new MenuItem("Show Details");
            detailsItem.setOnAction(e -> {
                StaticIssue issue = cell.getTableRow() == null ? null : cell.getTableRow().getItem();
                if (issue != null) {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Issue Details");
                    alert.setHeaderText(null);
                    TextArea textArea = new TextArea(buildIssueDetails(issue));
                    textArea.setEditable(false);
                    textArea.setWrapText(true);
                    alert.getDialogPane().setContent(textArea);
                    alert.showAndWait();
                }
            });

            MenuItem quickFixItem = new MenuItem("Apply Quick Fix");
            quickFixItem.setOnAction(e -> {
                StaticIssue issue = cell.getTableRow() == null ? null : cell.getTableRow().getItem();
                if (issue != null && issue.hasQuickFix() && applyQuickFix(issue)) {
                    if (!latestIssues.isEmpty()) {
                        List<StaticIssue> updated = new ArrayList<>(latestIssues);
                        updated.remove(issue);
                        latestIssues = updated;
                    }
                    applyIssueFilter(table);
                }
            });
            contextMenu.setOnShowing(e -> {
                StaticIssue issue = cell.getTableRow() == null ? null : cell.getTableRow().getItem();
                quickFixItem.setDisable(issue == null || !issue.hasQuickFix());
            });
            
            contextMenu.getItems().addAll(copyItem, detailsItem, quickFixItem);
            cell.emptyProperty().addListener((obs, wasEmpty, isNowEmpty) -> {
                if (isNowEmpty) {
                    cell.setContextMenu(null);
                } else {
                    cell.setContextMenu(contextMenu);
                }
            });
            
            return cell;
        });
        
        TableColumn<StaticIssue, String> sevCol = new TableColumn<>("Severity");
        sevCol.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().getSeverity()));

        TableColumn<StaticIssue, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(v -> new ReadOnlyStringWrapper(getIssueStatus(v.getValue())));
        statusCol.setPrefWidth(90);
        statusCol.setMaxWidth(120);

        TableColumn<StaticIssue, String> fixabilityCol = new TableColumn<>("Fixability");
        fixabilityCol.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().getFixability()));
        fixabilityCol.setPrefWidth(100);
        fixabilityCol.setMaxWidth(130);
        
        table.getColumns().addAll(fileCol, lineCol, descCol, sevCol, statusCol, fixabilityCol);
        
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null) {
                if (Files.isRegularFile(newVal.getFile())) {
                    loadFileContent(newVal.getFile());
                    codeArea.moveTo(Math.max(0, newVal.getLineNumber() - 1), 0);
                    codeArea.requestFollowCaret();
                }
            }
        });
        
        return table;
    }

    private TableView<CompileErrorRow> createCompileErrorsTable() {
        TableView<CompileErrorRow> table = new TableView<>(compileErrors);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No compile errors detected."));

        TableColumn<CompileErrorRow, String> fileCol = new TableColumn<>("File");
        fileCol.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().getDisplayFile(rootPath)));

        TableColumn<CompileErrorRow, Number> lineCol = new TableColumn<>("Line");
        lineCol.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue().getLine()));
        lineCol.setPrefWidth(60);
        lineCol.setMaxWidth(80);

        TableColumn<CompileErrorRow, Number> colCol = new TableColumn<>("Col");
        colCol.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue().getColumn()));
        colCol.setPrefWidth(60);
        colCol.setMaxWidth(80);

        TableColumn<CompileErrorRow, String> msgCol = new TableColumn<>("Message");
        msgCol.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().getMessage()));
        msgCol.setCellFactory(tc -> new TableCell<>() {
            private final Label label = new Label();
            {
                label.setWrapText(true);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setGraphic(label);
                label.maxWidthProperty().bind(tc.widthProperty().subtract(12));
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    label.setText(null);
                    setTooltip(null);
                } else {
                    label.setText(item);
                    setTooltip(new Tooltip(item));
                }
            }
        });

        TableColumn<CompileErrorRow, String> detailsCol = new TableColumn<>("Details");
        detailsCol.setCellValueFactory(v -> new ReadOnlyStringWrapper(toMultilineDetails(v.getValue().getDetails())));
        detailsCol.setCellFactory(tc -> new TableCell<>() {
            private final Label label = new Label();
            private final Hyperlink toggle = new Hyperlink();
            private final VBox container = new VBox(2);
            {
                label.setWrapText(true);
                toggle.setOnAction(e -> {
                    CompileErrorRow rowItem = getTableRow() == null ? null : getTableRow().getItem();
                    if (rowItem == null) {
                        return;
                    }
                    rowItem.setDetailsExpanded(!rowItem.isDetailsExpanded());
                    updateItem(getItem(), false);
                });
                container.getChildren().addAll(label, toggle);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setGraphic(container);
                label.maxWidthProperty().bind(tc.widthProperty().subtract(12));
                toggle.setVisible(false);
                toggle.setManaged(false);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    label.setText(null);
                    toggle.setVisible(false);
                    toggle.setManaged(false);
                    setTooltip(null);
                } else {
                    CompileErrorRow rowItem = getTableRow() == null ? null : getTableRow().getItem();
                    boolean expanded = rowItem != null && rowItem.isDetailsExpanded();
                    boolean expandable = isExpandableDetails(item);
                    label.setText(expanded || !expandable ? item : compactDetailsPreview(item));
                    toggle.setVisible(expandable);
                    toggle.setManaged(expandable);
                    if (expandable) {
                        toggle.setText(expanded ? "Show less" : "Show more");
                    }
                    setTooltip(new Tooltip(item));
                }
            }
        });

        table.getColumns().addAll(fileCol, lineCol, colCol, msgCol, detailsCol);
        table.setRowFactory(tv -> {
            TableRow<CompileErrorRow> row = new TableRow<>();
            MenuItem copyItem = new MenuItem("Copy Error");
            copyItem.setOnAction(e -> copyCompileErrorToClipboard(row.getItem()));
            MenuItem showDetailsItem = new MenuItem("Show Full Error Details");
            showDetailsItem.setOnAction(e -> showCompileErrorDetails(row.getItem()));
            ContextMenu rowMenu = new ContextMenu(copyItem, showDetailsItem);
            rowMenu.setOnShowing(e -> {
                CompileErrorRow item = row.getItem();
                boolean disabled = item == null;
                copyItem.setDisable(disabled);
                showDetailsItem.setDisable(disabled);
            });
            row.emptyProperty().addListener((obs, wasEmpty, isEmpty) -> row.setContextMenu(isEmpty ? null : rowMenu));
            return row;
        });
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selected) -> {
            if (selected == null || selected.getFile() == null) {
                return;
            }
            Path file = selected.getFile();
            if (!Files.isRegularFile(file)) {
                return;
            }
            loadFileContent(file);
            codeArea.moveTo(Math.max(0, selected.getLine() - 1), 0);
            codeArea.requestFollowCaret();
        });
        return table;
    }

    private String toMultilineDetails(String details) {
        if (details == null || details.isBlank()) {
            return "";
        }
        return details.replace(" | ", System.lineSeparator());
    }

    private boolean isExpandableDetails(String details) {
        if (details == null || details.isBlank()) {
            return false;
        }
        String[] lines = details.split("\\R", -1);
        return lines.length > 1 || details.length() > 120;
    }

    private String compactDetailsPreview(String details) {
        if (details == null || details.isBlank()) {
            return "";
        }
        String[] lines = details.split("\\R", -1);
        if (lines.length <= 1 && details.length() <= 120) {
            return details;
        }
        if (lines.length > 1) {
            String first = lines[0];
            String second = lines.length > 1 ? lines[1] : "";
            int remaining = Math.max(0, lines.length - 2);
            if (remaining == 0) {
                return first + System.lineSeparator() + second;
            }
            return first + System.lineSeparator() + second + System.lineSeparator()
                    + "... (" + remaining + " more line(s))";
        }
        return details.substring(0, 117) + "...";
    }

    private void copyCompileErrorToClipboard(CompileErrorRow error) {
        if (error == null) {
            return;
        }
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(formatCompileErrorForClipboard(error));
        clipboard.setContent(content);
    }

    private String formatCompileErrorForClipboard(CompileErrorRow error) {
        if (error == null) {
            return "";
        }
        String details = toMultilineDetails(error.getDetails());
        StringBuilder text = new StringBuilder();
        text.append("File: ").append(error.getDisplayFile(rootPath)).append(System.lineSeparator());
        text.append("Line: ").append(error.getLine()).append(System.lineSeparator());
        text.append("Column: ").append(error.getColumn()).append(System.lineSeparator());
        text.append("Message: ").append(error.getMessage());
        if (!details.isBlank()) {
            text.append(System.lineSeparator()).append("Details:").append(System.lineSeparator()).append(details);
        }
        return text.toString();
    }

    private void showCompileErrorDetails(CompileErrorRow error) {
        if (error == null) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Compile Error Details");
        alert.setHeaderText(error.getDisplayFile(rootPath) + ":" + error.getLine() + ":" + error.getColumn());
        TextArea detailsArea = new TextArea(formatCompileErrorForClipboard(error));
        detailsArea.setEditable(false);
        detailsArea.setWrapText(false);
        detailsArea.setPrefColumnCount(90);
        detailsArea.setPrefRowCount(16);
        alert.getDialogPane().setContent(detailsArea);
        ButtonType copyBtn = new ButtonType("Copy", ButtonBar.ButtonData.LEFT);
        ButtonType closeBtn = new ButtonType("Close", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(copyBtn, closeBtn);
        Node copyButtonNode = alert.getDialogPane().lookupButton(copyBtn);
        if (copyButtonNode != null) {
            copyButtonNode.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
                copyCompileErrorToClipboard(error);
                evt.consume();
            });
        }
        alert.showAndWait();
    }
    
    private void runScopedAnalysis(TableView<StaticIssue> problemsTable, StaticAnalyzer analyzer, String scope, Runnable onComplete) {
        if (rootPath == null) {
            onComplete.run();
            return;
        }

        String normalizedScope = scope == null ? "Project" : scope;
        TreeItem<File> selectedItem = fileTree.getSelectionModel().getSelectedItem();
        Path selectedFile = selectedItem != null && selectedItem.getValue() != null && selectedItem.getValue().isFile()
                ? selectedItem.getValue().toPath()
                : null;
        Path selectedDirectory = resolveSelectedDirectory(selectedItem);

        if ("Current File".equals(normalizedScope) && selectedFile == null) {
            showWarning("No File Selected", "Please select a file for Current File scope.");
            onComplete.run();
            return;
        }
        if ("Module/Folder".equals(normalizedScope) && selectedDirectory == null) {
            showWarning("No Folder Selected", "Please select a folder (or a file inside it) for Module/Folder scope.");
            onComplete.run();
            return;
        }

        Task<List<StaticIssue>> task = new Task<>() {
            @Override
            protected List<StaticIssue> call() {
                if ("Current File".equals(normalizedScope) && selectedFile != null && analyzer.canAnalyzeSingleFile()) {
                    return analyzer.analyzeFile(selectedFile);
                }

                List<StaticIssue> allIssues = analyzer.analyzeProject(rootPath);
                if ("Current File".equals(normalizedScope) && selectedFile != null) {
                    Path normalizedTarget = selectedFile.toAbsolutePath().normalize();
                    return allIssues.stream()
                            .filter(issue -> issue != null && issue.getFile() != null)
                            .filter(issue -> issue.getFile().toAbsolutePath().normalize().equals(normalizedTarget))
                            .collect(Collectors.toList());
                }
                if ("Module/Folder".equals(normalizedScope) && selectedDirectory != null) {
                    Path normalizedTarget = selectedDirectory.toAbsolutePath().normalize();
                    return allIssues.stream()
                            .filter(issue -> issue != null && issue.getFile() != null)
                            .filter(issue -> issue.getFile().toAbsolutePath().normalize().startsWith(normalizedTarget))
                            .collect(Collectors.toList());
                }
                return allIssues;
            }
        };

        task.setOnSucceeded(e -> {
            handleAnalysisResults(problemsTable, analyzer.getName(), task.getValue());
            onComplete.run();
        });

        task.setOnFailed(e -> {
            onComplete.run();
            Throwable ex = task.getException();
            if (ex != null) {
                LOGGER.error("Scoped static analysis failed for scope {}", normalizedScope, ex);
            }
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private Path resolveSelectedDirectory(TreeItem<File> selectedItem) {
        if (selectedItem == null || selectedItem.getValue() == null) {
            return null;
        }
        File file = selectedItem.getValue();
        if (file.isDirectory()) {
            return file.toPath();
        }
        File parent = file.getParentFile();
        return parent == null ? null : parent.toPath();
    }

    private void handleAnalysisResults(TableView<StaticIssue> problemsTable, String analyzerName, List<StaticIssue> issues) {
        latestAnalyzerName = analyzerName == null ? "" : analyzerName;
        latestIssues = issues == null ? Collections.emptyList() : new ArrayList<>(issues);
        baselineFingerprints = loadBaseline(latestAnalyzerName);
        applyIssueFilter(problemsTable);
    }

    private void applyIssueFilter(TableView<StaticIssue> problemsTable) {
        if (problemsTable == null) {
            return;
        }

        List<StaticIssue> source = latestIssues == null ? Collections.emptyList() : latestIssues;
        List<StaticIssue> visible = source.stream()
                .filter(issue -> !showNewIssuesOnly || isNewIssue(issue))
                .collect(Collectors.toList());
        problemsTable.setItems(FXCollections.observableArrayList(visible));
        updateIssueSummaryLabel(source, visible);
        problemsTable.refresh();
    }

    private void updateIssueSummaryLabel(List<StaticIssue> allIssues, List<StaticIssue> visibleIssues) {
        int total = allIssues == null ? 0 : allIssues.size();
        int visible = visibleIssues == null ? 0 : visibleIssues.size();
        int newCount = allIssues == null ? 0 : (int) allIssues.stream().filter(this::isNewIssue).count();
        int existing = Math.max(0, total - newCount);
        issueSummaryLabel.setText(
                String.format(
                        Locale.US,
                        "Issues: %d shown / %d total (New: %d, Existing: %d)",
                        visible,
                        total,
                        newCount,
                        existing
                )
        );
    }

    private boolean isNewIssue(StaticIssue issue) {
        if (issue == null) {
            return false;
        }
        String fingerprint = issue.fingerprint(rootPath);
        return !baselineFingerprints.contains(fingerprint);
    }

    private String getIssueStatus(StaticIssue issue) {
        return isNewIssue(issue) ? "New" : "Existing";
    }

    private void applyAllSafeQuickFixes(TableView<StaticIssue> table) {
        List<StaticIssue> visibleIssues = table == null ? Collections.emptyList() : new ArrayList<>(table.getItems());
        if (visibleIssues.isEmpty()) {
            return;
        }

        List<StaticIssue> safeIssues = visibleIssues.stream()
                .filter(issue -> issue != null && issue.hasQuickFix())
                .filter(issue -> "safe".equalsIgnoreCase(issue.getFixability()))
                .collect(Collectors.toList());
        if (safeIssues.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (StaticIssue issue : safeIssues) {
            if (applyQuickFix(issue, false)) {
                changed = true;
                latestIssues = latestIssues.stream()
                        .filter(existing -> existing != issue)
                        .collect(Collectors.toList());
            }
        }

        if (changed) {
            applyIssueFilter(table);
            runCompileSafetyGate();
        }
    }

    private void showWarning(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private Set<String> collectFingerprints(List<StaticIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> fingerprints = new LinkedHashSet<>();
        for (StaticIssue issue : issues) {
            fingerprints.add(issue.fingerprint(rootPath));
        }
        return fingerprints;
    }

    private Set<String> loadBaseline(String analyzerName) {
        if (rootPath == null || analyzerName == null || analyzerName.isBlank()) {
            return Collections.emptySet();
        }
        Path baselineFile = getBaselineFilePath(analyzerName);
        if (!Files.isRegularFile(baselineFile)) {
            return Collections.emptySet();
        }
        try {
            return new LinkedHashSet<>(Files.readAllLines(baselineFile, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            LOGGER.error("Failed to read baseline file {}", baselineFile, ex);
            return Collections.emptySet();
        }
    }

    private void saveBaseline(String analyzerName, List<StaticIssue> issues) {
        if (rootPath == null || analyzerName == null || analyzerName.isBlank()) {
            return;
        }
        Path baselineFile = getBaselineFilePath(analyzerName);
        try {
            Files.createDirectories(baselineFile.getParent());
            Set<String> fingerprints = collectFingerprints(issues);
            Files.write(
                    baselineFile,
                    fingerprints,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ex) {
            LOGGER.error("Failed to write baseline file {}", baselineFile, ex);
        }
    }

    private Path getBaselineFilePath(String analyzerName) {
        String safeName = analyzerName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return rootPath.resolve(".metrics-analyzer").resolve("baselines").resolve(safeName + ".baseline");
    }

    private String buildIssueDetails(StaticIssue issue) {
        String tagsText = issue.getTags().isEmpty() ? "-" : String.join(", ", issue.getTags());
        String suggestedFix = issue.getSuggestedFix() == null || issue.getSuggestedFix().isBlank()
                ? "No suggested fix available."
                : issue.getSuggestedFix();
        return "Description: " + issue.getDescription() + System.lineSeparator()
                + "File: " + issue.getFile() + System.lineSeparator()
                + "Line: " + issue.getLineNumber() + System.lineSeparator()
                + "Severity: " + issue.getSeverity() + System.lineSeparator()
                + "Status: " + getIssueStatus(issue) + System.lineSeparator()
                + "Category: " + issue.getCategory() + System.lineSeparator()
                + "Rule: " + issue.getRuleId() + System.lineSeparator()
                + "Tool: " + issue.getTool() + System.lineSeparator()
                + "Confidence: " + String.format(Locale.US, "%.2f", issue.getConfidence()) + System.lineSeparator()
                + "Fixability: " + issue.getFixability() + System.lineSeparator()
                + "Effort: " + issue.getEffort() + System.lineSeparator()
                + "Tags: " + tagsText + System.lineSeparator()
                + "Suggested Fix: " + suggestedFix;
    }

    private void runCoverageAnalysis(Label coverageStatusLabel, Runnable onComplete) {
        if (rootPath == null) {
            coverageStatusLabel.setText("Coverage: Select a project first");
            onComplete.run();
            return;
        }

        coverageStatusLabel.setText("Coverage: Generating report...");
        Task<CoverageData> task = new Task<>() {
            @Override
            protected CoverageData call() {
                return coverageReportService.generateAndLoadCoverage(rootPath);
            }
        };

        task.setOnSucceeded(e -> {
            CoverageData data = task.getValue();
            coverageByFile = data.getLinesByFile();
            rebuildCoverageSummaries();
            applyCoverageForCurrentFile();
            fileTree.refresh();
            coverageStatusLabel.setText(
                    "Coverage: " + data.getReportType() + " loaded (" + data.getFileCount() + " files, " + data.getLineCount() + " lines)"
            );
            onComplete.run();
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            clearCoverageState();
            if (ex != null) {
                LOGGER.error("Coverage generation failed for {}", rootPath, ex);
                coverageStatusLabel.setText("Coverage: Failed - " + ex.getMessage());
            } else {
                coverageStatusLabel.setText("Coverage: Failed");
            }
            onComplete.run();
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void clearCoverageInfo(Label coverageStatusLabel) {
        clearCoverageState();
        coverageStatusLabel.setText("Coverage: Cleared");
    }

    private void clearCoverageState() {
        coverageByFile = Collections.emptyMap();
        coverageSummaryByPath = Collections.emptyMap();
        currentFileCoverage = Collections.emptyMap();
        applyCoverageForCurrentFile();
        fileTree.refresh();
    }

    private TreeView<File> createFileTree() {
        TreeView<File> tree = new TreeView<>();
        tree.setShowRoot(true);
        tree.setCellFactory(param -> new FileTreeCell());
        
        tree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                File file = newVal.getValue();
                if (file != null && file.isFile()) {
                    loadFileContent(file.toPath());
                }
                // Force children loading when item is selected
                if (file != null && file.isDirectory()) {
                    newVal.getChildren();
                }
            }
        });
        
        return tree;
    }

    private CodeArea createCodeArea() {
        CodeArea area = new CodeArea();
        area.setParagraphGraphicFactory(createCoverageAwareLineFactory(area));
        area.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 12;");
        area.setEditable(true);
        area.setWrapText(false);
        area.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!loadingFileContent && currentFilePath != null) {
                currentFileDirty = true;
                updateCurrentFileLabel();
            }
        });

        MenuItem renameItem = new MenuItem("Rename Symbol in File…");
        renameItem.setOnAction(e -> handleRenameSymbol(area));
        MenuItem extractConstantItem = new MenuItem("Extract Constant…");
        extractConstantItem.setOnAction(e -> handleExtractConstant(area, false));
        MenuItem extractConstantAllItem = new MenuItem("Extract Constant (All Occurrences)…");
        extractConstantAllItem.setOnAction(e -> handleExtractConstant(area, true));
        MenuItem extractMethodItem = new MenuItem("Extract Method from Selection…");
        extractMethodItem.setOnAction(e -> handleExtractMethod(area));
        ContextMenu codeAreaMenu = new ContextMenu(renameItem, extractConstantItem, extractConstantAllItem, extractMethodItem);
        codeAreaMenu.setOnShowing(e -> {
            String word = getWordAtCaret(area);
            String literal = getLiteralAtCaretOrSelection(area);
            int[] selectedRange = getSelectedLineRange(area);
            renameItem.setDisable(word == null || word.isBlank() || currentFilePath == null);
            renameItem.setText(word != null && !word.isBlank()
                    ? "Rename '" + word + "' in File…"
                    : "Rename Symbol in File…");
            extractConstantItem.setDisable(literal == null || literal.isBlank() || currentFilePath == null);
            extractConstantItem.setText(literal != null && !literal.isBlank()
                    ? "Extract Constant from '" + literal + "'…"
                    : "Extract Constant…");
            extractConstantAllItem.setDisable(literal == null || literal.isBlank() || currentFilePath == null);
            extractConstantAllItem.setText(literal != null && !literal.isBlank()
                    ? "Extract Constant (All) from '" + literal + "'…"
                    : "Extract Constant (All Occurrences)…");
            extractMethodItem.setDisable(selectedRange == null || currentFilePath == null);
            extractMethodItem.setText(selectedRange == null
                    ? "Extract Method from Selection…"
                    : "Extract Method (lines " + selectedRange[0] + "-" + selectedRange[1] + ")…");
        });
        area.setContextMenu(codeAreaMenu);

        return area;
    }

    private String getWordAtCaret(CodeArea area) {
        String selected = area.getSelectedText();
        if (selected != null && !selected.isBlank()
                && selected.chars().allMatch(c -> Character.isJavaIdentifierPart((char) c))) {
            return selected;
        }
        int caretPos = area.getCaretPosition();
        String text = area.getText();
        if (caretPos < 0 || caretPos > text.length()) {
            return null;
        }
        int pos = Math.min(caretPos, text.length() - 1);
        if (pos < 0) {
            return null;
        }
        // Expand left
        int start = pos;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        // Expand right
        int end = pos;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        if (start >= end) {
            return null;
        }
        String word = text.substring(start, end);
        return word.isBlank() ? null : word;
    }

    private void handleRenameSymbol(CodeArea area) {
        if (currentFilePath == null) {
            return;
        }
        String oldName = getWordAtCaret(area);
        if (oldName == null || oldName.isBlank()) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(oldName);
        dialog.setTitle("Rename Symbol");
        dialog.setHeaderText("Rename '" + oldName + "' in current file");
        dialog.setContentText("New name:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(newName -> {
            if (newName.isBlank() || newName.equals(oldName)) {
                return;
            }
            Map<String, String> attrs = new HashMap<>();
            attrs.put("oldName", oldName);
            attrs.put("newName", newName.trim());
            RefactoringAction action = new RefactoringAction(
                    RefactoringActionType.RENAME_SYMBOL,
                    currentFilePath.toAbsolutePath().normalize(),
                    -1,
                    attrs
            );
            try {
                RefactoringEngine engine = RefactoringEngine.createDefault();
                ProjectRefactoringState state = new ProjectRefactoringState(
                        currentFilePath.getParent() != null ? currentFilePath.getParent() : currentFilePath);
                RefactoringResult refResult = engine.apply(state, action);
                if (refResult.isModified()) {
                    loadFileContent(currentFilePath);
                    LOGGER.info("Rename: {}", refResult.getMessage());
                } else {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Rename Symbol");
                    alert.setHeaderText(null);
                    alert.setContentText(refResult.getMessage());
                    alert.showAndWait();
                }
            } catch (IOException ex) {
                LOGGER.error("Rename failed", ex);
            }
        });
    }

    private void handleExtractConstant(CodeArea area, boolean replaceAllOccurrences) {
        if (currentFilePath == null) {
            return;
        }
        String literal = getLiteralAtCaretOrSelection(area);
        if (literal == null || literal.isBlank()) {
            return;
        }

        String defaultName = "EXTRACTED_CONSTANT";
        TextInputDialog dialog = new TextInputDialog(defaultName);
        dialog.setTitle("Extract Constant");
        dialog.setHeaderText("Extract literal '" + literal + "' in current file");
        dialog.setContentText("Constant name:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (name == null || name.isBlank()) {
                return;
            }
            Map<String, String> attrs = new HashMap<>();
            attrs.put("literal", literal);
            attrs.put("constantName", name.trim());
            attrs.put("replaceAllOccurrences", Boolean.toString(replaceAllOccurrences));
            RefactoringAction action = new RefactoringAction(
                    RefactoringActionType.EXTRACT_CONSTANT,
                    currentFilePath.toAbsolutePath().normalize(),
                    area.getCurrentParagraph() + 1,
                    attrs
            );
            try {
                RefactoringResult refResult = refactoringEngine.apply(
                        new ProjectRefactoringState(rootPath != null ? rootPath : currentFilePath.getParent()),
                        action
                );
                if (refResult.isModified()) {
                    loadFileContent(currentFilePath);
                    runCompileSafetyGate();
                    LOGGER.info("Extract Constant: {}", refResult.getMessage());
                } else {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Extract Constant");
                    alert.setHeaderText(null);
                    alert.setContentText(refResult.getMessage());
                    alert.showAndWait();
                }
            } catch (IOException ex) {
                LOGGER.error("Extract constant failed", ex);
            }
        });
    }

    private void handleExtractMethod(CodeArea area) {
        if (currentFilePath == null) {
            return;
        }
        int[] selectedRange = getSelectedLineRange(area);
        if (selectedRange == null) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog("extractedMethod");
        dialog.setTitle("Extract Method");
        dialog.setHeaderText("Extract selected lines " + selectedRange[0] + "-" + selectedRange[1]);
        dialog.setContentText("Method name:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(methodName -> {
            if (methodName == null || methodName.isBlank()) {
                return;
            }
            Map<String, String> attrs = new HashMap<>();
            attrs.put("methodName", methodName.trim());
            attrs.put("startLine", Integer.toString(selectedRange[0]));
            attrs.put("endLine", Integer.toString(selectedRange[1]));

            RefactoringAction action = new RefactoringAction(
                    RefactoringActionType.EXTRACT_METHOD,
                    currentFilePath.toAbsolutePath().normalize(),
                    selectedRange[0],
                    attrs
            );
            try {
                RefactoringResult refResult = refactoringEngine.apply(
                        new ProjectRefactoringState(rootPath != null ? rootPath : currentFilePath.getParent()),
                        action
                );
                if (refResult.isModified()) {
                    loadFileContent(currentFilePath);
                    runCompileSafetyGate();
                    LOGGER.info("Extract Method: {}", refResult.getMessage());
                } else {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Extract Method");
                    alert.setHeaderText(null);
                    alert.setContentText(refResult.getMessage());
                    alert.showAndWait();
                }
            } catch (IOException ex) {
                LOGGER.error("Extract method failed", ex);
            }
        });
    }

    private int[] getSelectedLineRange(CodeArea area) {
        if (area == null) {
            return null;
        }
        IndexRange selection = area.getSelection();
        if (selection == null || selection.getLength() <= 0) {
            return null;
        }
        int startOffset = selection.getStart();
        int endOffset = Math.max(selection.getStart(), selection.getEnd() - 1);
        int startLine = area.offsetToPosition(startOffset, Bias.Forward).getMajor() + 1;
        int endLine = area.offsetToPosition(endOffset, Bias.Backward).getMajor() + 1;
        if (startLine <= 0 || endLine <= 0) {
            return null;
        }
        return new int[] {Math.min(startLine, endLine), Math.max(startLine, endLine)};
    }

    private String getLiteralAtCaretOrSelection(CodeArea area) {
        String selected = area.getSelectedText();
        if (selected != null) {
            String trimmed = selected.trim();
            if (isSupportedLiteral(trimmed)) {
                return trimmed;
            }
        }

        int caretPos = area.getCaretPosition();
        String text = area.getText();
        if (caretPos < 0 || caretPos > text.length() || text.isEmpty()) {
            return null;
        }
        int lineStart = text.lastIndexOf('\n', Math.max(0, caretPos - 1));
        int lineEnd = text.indexOf('\n', caretPos);
        int from = lineStart < 0 ? 0 : lineStart + 1;
        int to = lineEnd < 0 ? text.length() : lineEnd;
        String line = text.substring(from, to);
        int localCaret = Math.max(0, Math.min(line.length(), caretPos - from));

        Pattern literalPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)'|[-+]?\\d+(?:\\.\\d+)?(?:[fFdDlL])?|\\b(?:true|false)\\b");
        Matcher matcher = literalPattern.matcher(line);
        while (matcher.find()) {
            if (localCaret >= matcher.start() && localCaret <= matcher.end()) {
                String literal = matcher.group();
                return isSupportedLiteral(literal) ? literal : null;
            }
        }
        return null;
    }

    private boolean isSupportedLiteral(String literal) {
        if (literal == null || literal.isBlank()) {
            return false;
        }
        String v = literal.trim();
        return v.matches("\"(?:[^\"\\\\]|\\\\.)*\"")
                || v.matches("'(?:[^'\\\\]|\\\\.)'")
                || v.matches("[-+]?\\d+[lL]?")
                || v.matches("[-+]?(?:\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][-+]?\\d+)?[fFdD]?")
                || "true".equals(v)
                || "false".equals(v);
    }

    private IntFunction<Node> createCoverageAwareLineFactory(CodeArea area) {
        IntFunction<Node> lineFactory = LineNumberFactory.get(area);
        return lineIndex -> {
            Node lineNumberNode = lineFactory.apply(lineIndex);
            Map<Integer, CoverageLineStatus> activeCoverage = currentFileCoverage == null ? Collections.emptyMap() : currentFileCoverage;
            CoverageLineStatus status = activeCoverage.get(lineIndex + 1);
            Label marker = new Label(status == null ? " " : "●");
            marker.setMinWidth(9);
            marker.setPrefWidth(9);
            marker.setMaxWidth(9);
            if (status == null) {
                marker.setStyle("-fx-text-fill: transparent;");
            } else {
                marker.setStyle(coverageMarkerStyle(status));
                marker.setTooltip(new Tooltip(coverageTooltip(status)));
            }

            HBox lineGraphic = new HBox(4, marker, lineNumberNode);
            lineGraphic.setAlignment(Pos.CENTER_LEFT);
            return lineGraphic;
        };
    }

    private static String coverageMarkerStyle(CoverageLineStatus status) {
        if (status == CoverageLineStatus.COVERED) {
            return "-fx-text-fill: #16a34a;";
        }
        if (status == CoverageLineStatus.PARTIAL) {
            return "-fx-text-fill: #d97706;";
        }
        return "-fx-text-fill: #dc2626;";
    }

    private static String coverageTooltip(CoverageLineStatus status) {
        if (status == CoverageLineStatus.COVERED) {
            return "Covered";
        }
        if (status == CoverageLineStatus.PARTIAL) {
            return "Partially covered";
        }
        return "Not covered";
    }

    private void refreshFileTree(Path projectPath) {
        File root = projectPath.toFile();
        if (root.exists() && root.isDirectory()) {
            TreeItem<File> rootItem = buildTreeItem(root);
            fileTree.setRoot(rootItem);
            fileTree.setShowRoot(true);
            rootItem.setExpanded(true);
        }
    }

    private TreeItem<File> buildTreeItem(File file) {
        TreeItem<File> item = new TreeItem<File>(file) {
            private boolean childrenLoaded = false;

            @Override
            public boolean isLeaf() {
                if (getValue().isFile()) {
                    return true;
                }
                if (getValue().isDirectory()) {
                    return false;
                }
                return true;
            }

            @Override
            public ObservableList<TreeItem<File>> getChildren() {
                if (!childrenLoaded) {
                    childrenLoaded = true;
                    loadChildren();
                }
                return super.getChildren();
            }

            private void loadChildren() {
                if (getValue().isDirectory()) {
                    File[] files = getValue().listFiles(f -> !f.getName().startsWith("."));
                    if (files != null) {
                        Arrays.sort(files, (a, b) -> {
                            if (a.isDirectory() != b.isDirectory()) {
                                return a.isDirectory() ? -1 : 1;
                            }
                            return a.getName().compareTo(b.getName());
                        });

                        for (File child : files) {
                            if (child.isDirectory() || isTextFile(child)) {
                                super.getChildren().add(buildTreeItem(child));
                            }
                        }
                    }
                }
            }
        };
        return item;
    }

    private void loadFileContent(Path filePath) {
        currentFilePath = filePath.toAbsolutePath().normalize();
        loadingFileContent = true;
        updateFileContext(filePath);
        try {
            String filename = filePath.getFileName().toString();
            String rawContent = Files.readString(filePath, StandardCharsets.UTF_8);
            
            codeArea.clear();
            codeArea.appendText(rawContent);
            
            // Get the text directly from CodeArea to guarantee lengths match exactly.
            // CodeArea normalizes line endings internally when appending text.
            String content = codeArea.getText();
            
            // Apply syntax highlighting based on file type
            try {
                org.fxmisc.richtext.model.StyleSpans<java.util.Collection<String>> spans = null;
                
                if (filename.endsWith(".java")) {
                    spans = JavaSyntaxHighlighter.computeHighlighting(content);
                } else if (filename.endsWith(".xml") || filename.endsWith(".pom")) {
                    spans = XMLSyntaxHighlighter.computeHighlighting(content);
                } else if (filename.endsWith(".json")) {
                    spans = JSONSyntaxHighlighter.computeHighlighting(content);
                } else if (filename.endsWith(".yml") || filename.endsWith(".yaml")) {
                    spans = YAMLSyntaxHighlighter.computeHighlighting(content);
                } else if (filename.endsWith(".properties")) {
                    spans = PropertiesSyntaxHighlighter.computeHighlighting(content);
                }
                
                if (spans != null && spans.length() == content.length()) {
                    codeArea.setStyleSpans(0, spans);
                } else if (spans != null) {
                    LOGGER.warn("Span length mismatch for {}: expected {} but got {}", filename, content.length(), spans.length());
                }
            } catch (Exception e) {
                LOGGER.error("Syntax highlighting error for {}", filename, e);
            }

            applyCoverageForCurrentFile();
            codeArea.moveTo(0);
            currentFileDirty = false;
            updateCurrentFileLabel();
        } catch (IOException e) {
            LOGGER.error("Failed to read file {}", filePath, e);
            codeArea.clear();
            codeArea.appendText("Error reading file: " + e.getMessage());
            currentFileCoverage = Collections.emptyMap();
            refreshCoverageDecorations();
            updateCurrentFileLabel();
        } finally {
            loadingFileContent = false;
        }
    }

    private void saveCurrentFile() {
        if (currentFilePath == null) {
            return;
        }
        try {
            Files.writeString(currentFilePath, codeArea.getText(), StandardCharsets.UTF_8);
            currentFileDirty = false;
            updateCurrentFileLabel();
        } catch (IOException ex) {
            LOGGER.error("Failed to save file {}", currentFilePath, ex);
        }
    }

    private boolean applyQuickFix(StaticIssue issue) {
        return applyQuickFix(issue, true);
    }

    private boolean applyQuickFix(StaticIssue issue, boolean runSafetyGate) {
        if (issue == null || issue.getFile() == null || !Files.isRegularFile(issue.getFile())) {
            return false;
        }

        try {
            RefactoringAction action = RefactoringActionFactory.fromIssue(issue).orElse(null);
            if (action == null) {
                return false;
            }

            Path targetFile = issue.getFile().toAbsolutePath().normalize();
            Path backupPath = backupFile(targetFile);

            RefactoringResult result = refactoringEngine.apply(new ProjectRefactoringState(rootPath), action);
            if (!result.isModified()) {
                deleteQuietly(backupPath);
                return false;
            }

            // Track backup for undo
            clearLastFixBackup();
            lastFixOriginalPath = targetFile;
            lastFixBackupPath = backupPath;
            if (undoFixMenuItemRef != null) {
                undoFixMenuItemRef.setDisable(false);
                undoFixMenuItemRef.setText("Undo Last Fix (" + targetFile.getFileName() + ")");
            }

            if (currentFilePath != null && result.getModifiedFiles().stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .anyMatch(path -> path.equals(currentFilePath))) {
                loadFileContent(currentFilePath);
            }
            if (runSafetyGate) {
                runCompileSafetyGate();
            }
            return true;
        } catch (Exception ex) {
            LOGGER.error("Failed to apply quick fix for {}", issue.getFile(), ex);
            return false;
        }
    }

    /** Copies {@code source} to {@code source.bak} and returns the backup path, or null on failure. */
    private Path backupFile(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return null;
        }
        try {
            Path backup = source.resolveSibling(source.getFileName() + ".bak");
            Files.copy(source, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) {
            LOGGER.warn("Could not create backup for {}", source, e);
            return null;
        }
    }

    /** Restores the last backup. Returns true if successful. */
    private boolean undoLastFix() {
        if (lastFixOriginalPath == null || lastFixBackupPath == null
                || !Files.isRegularFile(lastFixBackupPath)) {
            return false;
        }
        try {
            Files.copy(lastFixBackupPath, lastFixOriginalPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Reverted {} from backup", lastFixOriginalPath);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to undo fix for {}", lastFixOriginalPath, e);
            return false;
        }
    }

    private void clearLastFixBackup() {
        deleteQuietly(lastFixBackupPath);
        lastFixOriginalPath = null;
        lastFixBackupPath = null;
        if (undoFixMenuItemRef != null) {
            undoFixMenuItemRef.setText("Undo Last Fix");
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // best effort
            }
        }
    }

    private void runCompileSafetyGate() {
        if (rootPath == null) {
            buildStatusLabel.setText("Build: Skipped (no project selected)");
            buildStatusLabel.setTooltip(null);
            compileErrors.clear();
            updateCompileErrorsSummary();
            return;
        }

        buildStatusLabel.setText("Build: Checking compile...");
        buildStatusLabel.setTooltip(null);

        Task<CompileCheckResult> task = new Task<>() {
            @Override
            protected CompileCheckResult call() {
                return runCompileCommand(rootPath);
            }
        };

        task.setOnSucceeded(event -> {
            CompileCheckResult result = task.getValue();
            if (result == null) {
                buildStatusLabel.setText("Build: Failed (unknown error)");
                buildStatusLabel.setTooltip(null);
                compileErrors.clear();
                updateCompileErrorsSummary();
                return;
            }
            compileErrors.setAll(result.errors);
            updateCompileErrorsSummary();
            if (result.success) {
                buildStatusLabel.setText("Build: Compile passed");
                buildStatusLabel.setTooltip(null);
            } else {
                buildStatusLabel.setText("Build: Compile failed");
                if (result.logTail != null && !result.logTail.isBlank()) {
                    buildStatusLabel.setTooltip(new Tooltip(result.logTail));
                } else {
                    buildStatusLabel.setTooltip(null);
                }
            }
        });

        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            buildStatusLabel.setText("Build: Failed");
            buildStatusLabel.setTooltip(ex == null ? null : new Tooltip(ex.getMessage()));
            compileErrors.clear();
            updateCompileErrorsSummary();
        });

        Thread worker = new Thread(task, "quick-fix-compile-check");
        worker.setDaemon(true);
        worker.start();
    }

    private CompileCheckResult runCompileCommand(Path projectRoot) {
        String mvnCmd = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "mvn.cmd" : "mvn";
        ProcessBuilder builder = new ProcessBuilder(
                mvnCmd,
                "compile",
                "-DskipTests",
                "-q"
        );
        builder.directory(projectRoot.toFile());
        builder.redirectErrorStream(true);

        Deque<String> lastLines = new ArrayDeque<>();
        List<CompileErrorRow> parsedErrors = new ArrayList<>();
        Set<String> seenErrorKeys = new LinkedHashSet<>();
        CompileErrorDraft currentError = null;
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lastLines.size() >= 12) {
                        lastLines.removeFirst();
                    }
                    lastLines.addLast(line);
                    CompileErrorDraft primary = parsePrimaryCompileErrorLine(line);
                    if (primary != null) {
                        addCompileErrorIfNew(currentError, seenErrorKeys, parsedErrors);
                        currentError = primary;
                        continue;
                    }

                    String secondary = parseCompileErrorSecondaryLine(line);
                    if (secondary != null && currentError != null) {
                        currentError.appendDetail(secondary);
                    }
                }
            }
            addCompileErrorIfNew(currentError, seenErrorKeys, parsedErrors);
            int exitCode = process.waitFor();
            String tail = String.join(System.lineSeparator(), lastLines);
            return new CompileCheckResult(exitCode == 0, tail, parsedErrors);
        } catch (Exception ex) {
            LOGGER.error("Compile safety gate failed for {}", projectRoot, ex);
            return new CompileCheckResult(false, ex.getMessage(), Collections.emptyList());
        }
    }

    private void addCompileErrorIfNew(CompileErrorDraft draft, Set<String> seenErrorKeys, List<CompileErrorRow> parsedErrors) {
        if (draft == null) {
            return;
        }
        CompileErrorRow parsed = draft.toRow();
        String key = parsed.getFile() + "|" + parsed.getLine() + "|" + parsed.getColumn()
                + "|" + parsed.getMessage() + "|" + parsed.getDetails();
        if (seenErrorKeys.add(key)) {
            parsedErrors.add(parsed);
        }
    }

    private CompileErrorDraft parsePrimaryCompileErrorLine(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = MAVEN_COMPILE_ERROR_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        Path file = normalizeCompilerPath(matcher.group(1));
        if (file == null) {
            return null;
        }
        int lineNumber = parsePositiveInt(matcher.group(2));
        int column = parsePositiveInt(matcher.group(3));
        String message = matcher.group(4) == null ? "" : matcher.group(4).trim();
        return new CompileErrorDraft(file, lineNumber, column, message);
    }

    private String parseCompileErrorSecondaryLine(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = MAVEN_COMPILE_DETAIL_PATTERN.matcher(line);
        if (matcher.find()) {
            return matcher.group(1) + ": " + matcher.group(2).trim();
        }
        if (line.startsWith("[ERROR] cannot find symbol")) {
            return "cannot find symbol";
        }
        return null;
    }

    private static int parsePositiveInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Path normalizeCompilerPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String normalized = rawPath.trim();
        // Maven on Windows often prefixes absolute paths with a leading slash: /D:/...
        if (normalized.length() > 2
                && normalized.charAt(0) == '/'
                && Character.isLetter(normalized.charAt(1))
                && normalized.charAt(2) == ':') {
            normalized = normalized.substring(1);
        }
        try {
            return Path.of(normalized).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return null;
        }
    }

    private void updateCompileErrorsSummary() {
        compileErrorsSummaryLabel.setText("Compile errors: " + compileErrors.size());
    }

    private void applyCoverageForCurrentFile() {
        if (currentFilePath == null) {
            currentFileCoverage = Collections.emptyMap();
        } else {
            currentFileCoverage = coverageByFile.getOrDefault(currentFilePath, Collections.emptyMap());
        }
        refreshCoverageDecorations();
        updateCurrentFileLabel();
    }

    private void refreshCoverageDecorations() {
        codeArea.setParagraphGraphicFactory(createCoverageAwareLineFactory(codeArea));
        for (int i = 0; i < codeArea.getParagraphs().size(); i++) {
            codeArea.setParagraphStyle(i, Collections.emptyList());
        }
        for (Map.Entry<Integer, CoverageLineStatus> entry : currentFileCoverage.entrySet()) {
            int paragraphIndex = entry.getKey() - 1;
            if (paragraphIndex < 0 || paragraphIndex >= codeArea.getParagraphs().size()) {
                continue;
            }
            codeArea.setParagraphStyle(paragraphIndex, Collections.singleton(coverageParagraphStyle(entry.getValue())));
        }
    }

    private static String coverageParagraphStyle(CoverageLineStatus status) {
        if (status == CoverageLineStatus.COVERED) {
            return "coverage-line-covered";
        }
        if (status == CoverageLineStatus.PARTIAL) {
            return "coverage-line-partial";
        }
        return "coverage-line-missed";
    }

    private void updateFileContext(Path filePath) {
        breadcrumbsLabel.setText("Path: " + buildBreadcrumb(filePath));
        updateCurrentFileLabel();
    }

    private void updateCurrentFileLabel() {
        if (currentFilePath == null) {
            currentFileLabel.setText("File: No file selected");
            return;
        }

        String filename = currentFilePath.getFileName() != null ? currentFilePath.getFileName().toString() : currentFilePath.toString();
        String dirtySuffix = currentFileDirty ? " *" : "";
        CoverageSummary summary = coverageSummaryByPath.get(currentFilePath);
        if (summary == null || summary.totalLines == 0) {
            currentFileLabel.setText("File: " + filename + dirtySuffix + (coverageByFile.isEmpty() ? "" : " | Coverage: n/a"));
            return;
        }

        currentFileLabel.setText(
                String.format(
                        Locale.US,
                        "File: %s%s | Coverage: %.1f%% (C:%d P:%d M:%d)",
                        filename,
                        dirtySuffix,
                        summary.coveragePercent(),
                        summary.coveredLines,
                        summary.partialLines,
                        summary.missedLines
                )
        );
    }

    private void rebuildCoverageSummaries() {
        if (coverageByFile.isEmpty() || rootPath == null) {
            coverageSummaryByPath = Collections.emptyMap();
            return;
        }

        Path normalizedRoot = rootPath.toAbsolutePath().normalize();
        Map<Path, CoverageSummary> summaries = new HashMap<>();
        for (Map.Entry<Path, Map<Integer, CoverageLineStatus>> entry : coverageByFile.entrySet()) {
            Path filePath = entry.getKey().toAbsolutePath().normalize();
            CoverageSummary fileSummary = CoverageSummary.fromLineStatuses(entry.getValue());
            if (fileSummary.totalLines == 0) {
                continue;
            }

            mergeCoverageSummary(summaries, filePath, fileSummary);

            Path parent = filePath.getParent();
            while (parent != null && parent.startsWith(normalizedRoot)) {
                mergeCoverageSummary(summaries, parent, fileSummary);
                if (parent.equals(normalizedRoot)) {
                    break;
                }
                parent = parent.getParent();
            }
        }

        coverageSummaryByPath = summaries;
    }

    private static void mergeCoverageSummary(Map<Path, CoverageSummary> summaries, Path path, CoverageSummary summaryToAdd) {
        CoverageSummary existing = summaries.get(path);
        if (existing == null) {
            summaries.put(path, summaryToAdd.copy());
            return;
        }

        existing.coveredLines += summaryToAdd.coveredLines;
        existing.partialLines += summaryToAdd.partialLines;
        existing.missedLines += summaryToAdd.missedLines;
        existing.totalLines += summaryToAdd.totalLines;
    }

    private String buildBreadcrumb(Path filePath) {
        if (rootPath == null) {
            return filePath.toString();
        }

        try {
            Path relative = rootPath.relativize(filePath);
            String path = StreamSupport.stream(relative.spliterator(), false)
                    .map(Path::toString)
                    .reduce((left, right) -> left + " > " + right)
                    .orElse(filenameFromPath(filePath));
            return filenameFromPath(rootPath) + " > " + path;
        } catch (IllegalArgumentException ex) {
            return filePath.toString();
        }
    }

    private String filenameFromPath(Path path) {
        Path name = path.getFileName();
        return name != null ? name.toString() : path.toString();
    }

    private static boolean isTextFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".java") || name.endsWith(".txt") || 
               name.endsWith(".xml") || name.endsWith(".json") || 
               name.endsWith(".properties") || name.endsWith(".md") ||
               name.endsWith(".yml") || name.endsWith(".yaml") ||
               name.endsWith(".gradle") || name.endsWith(".gitignore") ||
               name.endsWith(".class");
    }

    public void setProjectPath(Path projectPath) {
        this.rootPath = projectPath;
        refreshFileTree(projectPath);
        codeArea.clear();
        currentFilePath = null;
        currentFileDirty = false;
        latestIssues = Collections.emptyList();
        baselineFingerprints = Collections.emptySet();
        latestAnalyzerName = "";
        showNewIssuesOnly = false;
        issueSummaryLabel.setText("Issues: 0");
        buildStatusLabel.setText("Build: Not checked");
        buildStatusLabel.setTooltip(null);
        compileErrors.clear();
        updateCompileErrorsSummary();
        clearCoverageState();
        updateCurrentFileLabel();
        breadcrumbsLabel.setText("Path: -");
    }

    private static final class CompileCheckResult {
        private final boolean success;
        private final String logTail;
        private final List<CompileErrorRow> errors;

        private CompileCheckResult(boolean success, String logTail, List<CompileErrorRow> errors) {
            this.success = success;
            this.logTail = logTail;
            this.errors = errors == null ? Collections.emptyList() : errors;
        }
    }

    private static final class CompileErrorDraft {
        private final Path file;
        private final int line;
        private final int column;
        private final String message;
        private final List<String> details = new ArrayList<>();

        private CompileErrorDraft(Path file, int line, int column, String message) {
            this.file = file;
            this.line = line;
            this.column = column;
            this.message = message == null ? "" : message;
        }

        private void appendDetail(String detail) {
            if (detail == null || detail.isBlank()) {
                return;
            }
            details.add(detail.trim());
        }

        private CompileErrorRow toRow() {
            String joinedDetails = details.isEmpty() ? "" : String.join(" | ", details);
            return new CompileErrorRow(file, line, column, message, joinedDetails);
        }
    }

    private static final class CompileErrorRow {
        private final Path file;
        private final int line;
        private final int column;
        private final String message;
        private final String details;
        private boolean detailsExpanded;

        private CompileErrorRow(Path file, int line, int column, String message, String details) {
            this.file = file;
            this.line = line;
            this.column = column;
            this.message = message == null ? "" : message;
            this.details = details == null ? "" : details;
        }

        private Path getFile() {
            return file;
        }

        private int getLine() {
            return line;
        }

        private int getColumn() {
            return column;
        }

        private String getMessage() {
            return message;
        }

        private String getDetails() {
            return details;
        }

        private boolean isDetailsExpanded() {
            return detailsExpanded;
        }

        private void setDetailsExpanded(boolean detailsExpanded) {
            this.detailsExpanded = detailsExpanded;
        }

        private String getDisplayFile(Path projectRoot) {
            if (file == null) {
                return "";
            }
            if (projectRoot == null) {
                return file.toString();
            }
            try {
                return projectRoot.toAbsolutePath().normalize().relativize(file).toString();
            } catch (Exception ex) {
                return file.toString();
            }
        }
    }

    private class FileTreeCell extends TreeCell<File> {
        @Override
        protected void updateItem(File file, boolean empty) {
            super.updateItem(file, empty);
            
            if (empty || file == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
            } else {
                String icon = file.isDirectory() ? "\uD83D\uDCC1" : "\uD83D\uDCC4";
                String displayName = file.getName().isEmpty() ? file.getPath() : file.getName();
                Path path = file.toPath().toAbsolutePath().normalize();
                CoverageSummary summary = coverageSummaryByPath.get(path);
                if (summary == null || summary.totalLines == 0) {
                    setText(displayName);
                    setTooltip(null);
                } else {
                    setText(String.format(Locale.US, "%s (%.1f%%)", displayName, summary.coveragePercent()));
                    setTooltip(new Tooltip(String.format(
                            Locale.US,
                            "Coverage %.1f%% - Covered: %d, Partial: %d, Missed: %d",
                            summary.coveragePercent(),
                            summary.coveredLines,
                            summary.partialLines,
                            summary.missedLines
                    )));
                }
                setGraphic(new Label(icon));
            }
        }
    }

    private static class CoverageSummary {
        private int coveredLines;
        private int partialLines;
        private int missedLines;
        private int totalLines;

        private static CoverageSummary fromLineStatuses(Map<Integer, CoverageLineStatus> lineStatuses) {
            CoverageSummary summary = new CoverageSummary();
            for (CoverageLineStatus status : lineStatuses.values()) {
                if (status == CoverageLineStatus.COVERED) {
                    summary.coveredLines++;
                } else if (status == CoverageLineStatus.PARTIAL) {
                    summary.partialLines++;
                } else {
                    summary.missedLines++;
                }
                summary.totalLines++;
            }
            return summary;
        }

        private double coveragePercent() {
            if (totalLines == 0) {
                return 0.0;
            }
            double weightedCovered = coveredLines + (partialLines * 0.5);
            return weightedCovered * 100.0 / totalLines;
        }

        private CoverageSummary copy() {
            CoverageSummary copy = new CoverageSummary();
            copy.coveredLines = coveredLines;
            copy.partialLines = partialLines;
            copy.missedLines = missedLines;
            copy.totalLines = totalLines;
            return copy;
        }
    }
}
