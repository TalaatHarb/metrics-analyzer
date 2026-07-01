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
import net.talaatharb.analyzer.service.PMDStaticAnalyzer;
import net.talaatharb.analyzer.service.CheckstyleStaticAnalyzer;
import net.talaatharb.analyzer.service.SpotBugsStaticAnalyzer;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.concurrent.Task;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.Node;
import javafx.geometry.Pos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.StreamSupport;
import net.talaatharb.analyzer.service.CoverageReportService;
import net.talaatharb.analyzer.service.CoverageReportService.CoverageData;
import net.talaatharb.analyzer.service.CoverageReportService.CoverageLineStatus;

public class FileExplorerTab {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileExplorerTab.class);
    private final CodeArea codeArea;
    private final TreeView<File> fileTree;
    private final Label currentFileLabel;
    private final Label breadcrumbsLabel;
    private final CoverageReportService coverageReportService;
    private Path currentFilePath;
    private Map<Path, Map<Integer, CoverageLineStatus>> coverageByFile;
    private Map<Path, CoverageSummary> coverageSummaryByPath;
    private Map<Integer, CoverageLineStatus> currentFileCoverage;
    private Path rootPath;

    public FileExplorerTab() {
        this.coverageReportService = new CoverageReportService();
        this.coverageByFile = Collections.emptyMap();
        this.coverageSummaryByPath = Collections.emptyMap();
        this.currentFileCoverage = Collections.emptyMap();
        this.fileTree = createFileTree();
        this.codeArea = createCodeArea();
        this.currentFileLabel = new Label("File: No file selected");
        this.breadcrumbsLabel = new Label("Path: -");
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
        
        ComboBox<StaticAnalyzer> analyzerComboBox = new ComboBox<>();
        List<StaticAnalyzer> allAnalyzers = Arrays.asList(new BasicStaticAnalyzer(), new PMDStaticAnalyzer(), new CheckstyleStaticAnalyzer(), new SpotBugsStaticAnalyzer());
        analyzerComboBox.getItems().addAll(allAnalyzers);
        analyzerComboBox.getSelectionModel().selectFirst();
        
        javafx.scene.control.CheckBox scanCurrentFileBox = new javafx.scene.control.CheckBox("Scan Current File Only");
        scanCurrentFileBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            StaticAnalyzer selected = analyzerComboBox.getValue();
            analyzerComboBox.getItems().clear();
            if (newVal) {
                for (StaticAnalyzer analyzer : allAnalyzers) {
                    if (analyzer.canAnalyzeSingleFile()) {
                        analyzerComboBox.getItems().add(analyzer);
                    }
                }
            } else {
                analyzerComboBox.getItems().addAll(allAnalyzers);
            }
            if (analyzerComboBox.getItems().contains(selected)) {
                analyzerComboBox.getSelectionModel().select(selected);
            } else {
                analyzerComboBox.getSelectionModel().selectFirst();
            }
        });
        
        ProgressIndicator analysisProgress = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        analysisProgress.setPrefSize(16, 16);
        analysisProgress.setVisible(false);
        analysisProgress.setManaged(false);
        ProgressIndicator coverageProgress = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        coverageProgress.setPrefSize(16, 16);
        coverageProgress.setVisible(false);
        coverageProgress.setManaged(false);
        Label coverageStatusLabel = new Label("Coverage: Not loaded");
        coverageStatusLabel.setStyle("-fx-text-fill: #6b7280;");

        MenuButton operationsMenu = new MenuButton("Operations");
        MenuItem scanMenuItem = new MenuItem("Scan for Problems");
        MenuItem generateCoverageMenuItem = new MenuItem("Generate Coverage");
        MenuItem clearCoverageMenuItem = new MenuItem("Clear Coverage Info");
        operationsMenu.getItems().addAll(scanMenuItem, new SeparatorMenuItem(), generateCoverageMenuItem, clearCoverageMenuItem);

        scanMenuItem.setOnAction(e -> {
            operationsMenu.setDisable(true);
            analysisProgress.setVisible(true);
            analysisProgress.setManaged(true);
            if (scanCurrentFileBox.isSelected()) {
                TreeItem<File> selectedItem = fileTree.getSelectionModel().getSelectedItem();
                if (selectedItem != null && selectedItem.getValue() != null && selectedItem.getValue().isFile()) {
                    runSingleFileAnalysis(problemsTable, analyzerComboBox.getValue(), selectedItem.getValue().toPath(), () -> {
                        operationsMenu.setDisable(false);
                        analysisProgress.setVisible(false);
                        analysisProgress.setManaged(false);
                    });
                } else {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("No File Selected");
                    alert.setHeaderText(null);
                    alert.setContentText("Please select a file to scan.");
                    alert.showAndWait();
                    operationsMenu.setDisable(false);
                    analysisProgress.setVisible(false);
                    analysisProgress.setManaged(false);
                }
            } else {
                runStaticAnalysis(problemsTable, analyzerComboBox.getValue(), () -> {
                    operationsMenu.setDisable(false);
                    analysisProgress.setVisible(false);
                    analysisProgress.setManaged(false);
                });
            }
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
                scanCurrentFileBox,
                operationsMenu,
                analysisProgress,
                coverageProgress,
                coverageStatusLabel
        );
        controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        VBox problemsContainer = new VBox(8, controls, problemsTable);
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
                if (cell.getItem() != null) {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Issue Description");
                    alert.setHeaderText(null);
                    TextArea textArea = new TextArea(cell.getItem());
                    textArea.setEditable(false);
                    textArea.setWrapText(true);
                    alert.getDialogPane().setContent(textArea);
                    alert.showAndWait();
                }
            });
            
            contextMenu.getItems().addAll(copyItem, detailsItem);
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
        
        table.getColumns().addAll(fileCol, lineCol, descCol, sevCol);
        
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null) {
                if (java.nio.file.Files.isRegularFile(newVal.getFile())) {
                    loadFileContent(newVal.getFile());
                    codeArea.moveTo(Math.max(0, newVal.getLineNumber() - 1), 0);
                    codeArea.requestFollowCaret();
                }
            }
        });
        
        return table;
    }
    
    private void runStaticAnalysis(TableView<StaticIssue> problemsTable, StaticAnalyzer analyzer, Runnable onComplete) {
        if (rootPath == null) {
            onComplete.run();
            return;
        }
        
        Task<List<StaticIssue>> task = new Task<>() {
            @Override
            protected List<StaticIssue> call() {
                return analyzer.analyzeProject(rootPath);
            }
        };
        
        task.setOnSucceeded(e -> {
            problemsTable.setItems(FXCollections.observableArrayList(task.getValue()));
            onComplete.run();
        });
        
        task.setOnFailed(e -> {
            onComplete.run();
            Throwable ex = task.getException();
            if (ex != null) {
                LOGGER.error("Project static analysis failed", ex);
            }
        });
        
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void runSingleFileAnalysis(TableView<StaticIssue> problemsTable, StaticAnalyzer analyzer, Path filePath, Runnable onComplete) {
        if (filePath == null) {
            onComplete.run();
            return;
        }
        
        Task<List<StaticIssue>> task = new Task<>() {
            @Override
            protected List<StaticIssue> call() {
                return analyzer.analyzeFile(filePath);
            }
        };
        
        task.setOnSucceeded(e -> {
            problemsTable.setItems(FXCollections.observableArrayList(task.getValue()));
            onComplete.run();
        });
        
        task.setOnFailed(e -> {
            onComplete.run();
            Throwable ex = task.getException();
            if (ex != null) {
                LOGGER.error("Single-file static analysis failed for {}", filePath, ex);
            }
        });
        
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
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
        area.setEditable(false);
        area.setWrapText(false);
        return area;
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
        } catch (IOException e) {
            LOGGER.error("Failed to read file {}", filePath, e);
            codeArea.clear();
            codeArea.appendText("Error reading file: " + e.getMessage());
            currentFileCoverage = Collections.emptyMap();
            refreshCoverageDecorations();
            updateCurrentFileLabel();
        }
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
        CoverageSummary summary = coverageSummaryByPath.get(currentFilePath);
        if (summary == null || summary.totalLines == 0) {
            currentFileLabel.setText("File: " + filename + (coverageByFile.isEmpty() ? "" : " | Coverage: n/a"));
            return;
        }

        currentFileLabel.setText(
                String.format(
                        Locale.US,
                        "File: %s | Coverage: %.1f%% (C:%d P:%d M:%d)",
                        filename,
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
        clearCoverageState();
        updateCurrentFileLabel();
        breadcrumbsLabel.setText("Path: -");
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
            return (weightedCovered * 100.0) / totalLines;
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
