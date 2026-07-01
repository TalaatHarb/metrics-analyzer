package net.talaatharb.analyzer;

import net.talaatharb.analyzer.model.AnalysisResult;
import net.talaatharb.analyzer.model.ClassMetrics;
import net.talaatharb.analyzer.model.DependencyRelation;
import net.talaatharb.analyzer.service.JavaSourceProjectAnalyzer;
import net.talaatharb.analyzer.service.MetricsAnalyzerService;
import net.talaatharb.analyzer.ui.FileExplorerTab;
import net.talaatharb.analyzer.ui.GitCommitsTab;
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.view.mxGraph;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MetricsAnalyzerApp extends Application {
    private static final String GRAPH_CLASS_LEVEL = "Class/File Coupling";
    private static final String GRAPH_PACKAGE_LEVEL = "Package Coupling";
    private static final String EDGE_STYLE_NORMAL =
            "endArrow=classic;strokeWidth=1.5;curved=1;";
    private static final String EDGE_STYLE_BIDIRECTIONAL =
            "endArrow=classic;strokeColor=#dc2626;strokeWidth=2.2;curved=1;";
    private static final String NODE_STYLE =
            "shape=rectangle;rounded=1;arcSize=10;fillColor=#eef2ff;strokeColor=#4f46e5;fontSize=11;";

    private static final String[] EDGE_COLORS = {
            "#3b82f6", "#10b981", "#f59e0b", "#8b5cf6", "#6366f1", "#ec4899", "#14b8a6", "#f43f5e"
    };

    private final MetricsAnalyzerService analyzer = new JavaSourceProjectAnalyzer();
    private final ObservableList<ClassMetrics> rows = FXCollections.observableArrayList();

    private Label selectedFolderLabel;
    private Label statusLabel;
    private TextArea summaryArea;
    private TableView<ClassMetrics> table;
    private SwingNode graphNode;
    private ComboBox<String> graphLevelCombo;
    private FileExplorerTab fileExplorerTab;
    private GitCommitsTab gitCommitsTab;

    private Path selectedProjectPath;
    private AnalysisResult latestResult;

    @Override
    public void start(Stage stage) {
        selectedFolderLabel = new Label("No folder selected");
        statusLabel = new Label("Ready (" + analyzer.getDisplayName() + ")");

        Button chooseButton = new Button("Choose Project Folder");
        Button analyzeButton = new Button("Analyze");
        analyzeButton.setDisable(true);

        chooseButton.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Java Project Folder");
            if (selectedProjectPath != null) {
                File existing = selectedProjectPath.toFile();
                if (existing.exists()) {
                    chooser.setInitialDirectory(existing);
                }
            }

            File selected = chooser.showDialog(stage);
            if (selected != null) {
                selectedProjectPath = selected.toPath();
                selectedFolderLabel.setText(selectedProjectPath.toString());
                statusLabel.setText("Folder selected");
                analyzeButton.setDisable(false);
                fileExplorerTab.setProjectPath(selectedProjectPath);
                gitCommitsTab.setProjectPath(selectedProjectPath);
            }
        });

        analyzeButton.setOnAction(event -> runAnalysis(analyzeButton));

        HBox actions = new HBox(10, chooseButton, analyzeButton);
        VBox top = new VBox(
                8,
                actions,
                new Label("Project Folder:"),
                selectedFolderLabel,
                statusLabel
        );
        top.setPadding(new Insets(12));

        fileExplorerTab = new FileExplorerTab();
        gitCommitsTab = new GitCommitsTab();
        
        TabPane tabs = new TabPane();
        tabs.getTabs().add(createMetricsTab());
        tabs.getTabs().add(createCouplingGraphTab());
        tabs.getTabs().add(fileExplorerTab.createTab(selectedProjectPath != null ? selectedProjectPath : new File(".").toPath()));
        tabs.getTabs().add(gitCommitsTab.createTab(selectedProjectPath != null ? selectedProjectPath : new File(".").toPath()));

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(tabs);

        Scene scene = new Scene(root, 1240, 760);
        stage.setTitle("Java Metrics Analyzer");
        stage.setScene(scene);
        stage.show();
    }

    private Tab createMetricsTab() {
        table = createTable();
        summaryArea = new TextArea();
        summaryArea.setEditable(false);
        summaryArea.setWrapText(true);
        summaryArea.setPrefRowCount(8);

        VBox content = new VBox(10, table, new Label("Summary"), summaryArea);
        content.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);

        Tab tab = new Tab("Metrics", content);
        tab.setClosable(false);
        return tab;
    }

    private Tab createCouplingGraphTab() {
        graphLevelCombo = new ComboBox<>();
        graphLevelCombo.getItems().addAll(GRAPH_CLASS_LEVEL, GRAPH_PACKAGE_LEVEL);
        graphLevelCombo.getSelectionModel().selectFirst();
        graphLevelCombo.setOnAction(event -> renderCouplingGraph());

        Label legend = new Label("Gray edges: one-way coupling | Red edges: two-way coupling");
        graphNode = new SwingNode();
        graphNode.boundsInLocalProperty().addListener((obs, oldBounds, newBounds) -> {
            SwingUtilities.invokeLater(() -> {
                if (graphNode.getContent() != null) {
                    graphNode.getContent().revalidate();
                    graphNode.getContent().repaint();
                }
            });
        });

        VBox controls = new VBox(
                8,
                new HBox(10, new Label("View:"), graphLevelCombo),
                legend
        );
        controls.setPadding(new Insets(12, 12, 0, 12));

        BorderPane content = new BorderPane();
        content.setTop(controls);
        content.setCenter(graphNode);
        BorderPane.setMargin(graphNode, new Insets(12));

        renderNoGraphMessage("Run an analysis to visualize coupling relations.");

        Tab tab = new Tab("Coupling Graph", content);
        tab.setClosable(false);
        return tab;
    }

    private TableView<ClassMetrics> createTable() {
        TableView<ClassMetrics> tv = new TableView<>(rows);
        tv.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ClassMetrics, String> classCol = new TableColumn<>("Class");
        classCol.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().getClassName()));

        TableColumn<ClassMetrics, String> pkgCol = new TableColumn<>("Package");
        pkgCol.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().getPackageName()));

        TableColumn<ClassMetrics, Number> locCol = new TableColumn<>("LOC");
        locCol.setCellValueFactory(v -> new ReadOnlyIntegerWrapper(v.getValue().getLinesOfCode()));

        TableColumn<ClassMetrics, Number> methodCol = new TableColumn<>("Methods");
        methodCol.setCellValueFactory(v -> new ReadOnlyIntegerWrapper(v.getValue().getMethodCount()));

        TableColumn<ClassMetrics, Number> fieldCol = new TableColumn<>("Fields");
        fieldCol.setCellValueFactory(v -> new ReadOnlyIntegerWrapper(v.getValue().getFieldCount()));

        TableColumn<ClassMetrics, Number> couplingCol = new TableColumn<>("Coupling");
        couplingCol.setCellValueFactory(v -> new ReadOnlyIntegerWrapper(v.getValue().getEfferentCoupling()));

        TableColumn<ClassMetrics, Number> lcomCol = new TableColumn<>("LCOM");
        lcomCol.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(round(v.getValue().getLcom())));

        TableColumn<ClassMetrics, Number> ccCol = new TableColumn<>("CC");
        ccCol.setCellValueFactory(v -> new ReadOnlyIntegerWrapper(v.getValue().getCyclomaticComplexity()));

        TableColumn<ClassMetrics, Number> wmcCol = new TableColumn<>("WMC");
        wmcCol.setCellValueFactory(v -> new ReadOnlyIntegerWrapper(v.getValue().getWeightedMethodsPerClass()));

        TableColumn<ClassMetrics, Number> rfcCol = new TableColumn<>("RFC");
        rfcCol.setCellValueFactory(v -> new ReadOnlyIntegerWrapper(v.getValue().getResponseForClass()));

        TableColumn<ClassMetrics, Number> miCol = new TableColumn<>("Maintainability");
        miCol.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(round(v.getValue().getMaintainabilityIndex())));

        tv.getColumns().addAll(classCol, pkgCol, locCol, methodCol, fieldCol, couplingCol, lcomCol, ccCol, wmcCol, rfcCol, miCol);
        return tv;
    }

    private void runAnalysis(Button analyzeButton) {
        if (selectedProjectPath == null) {
            statusLabel.setText("Choose a folder first");
            return;
        }
        if (!analyzer.supports(selectedProjectPath)) {
            statusLabel.setText("Selected folder is not supported by " + analyzer.getDisplayName());
            return;
        }

        analyzeButton.setDisable(true);
        statusLabel.setText("Running analysis...");
        summaryArea.clear();
        rows.clear();
        latestResult = null;
        renderNoGraphMessage("Analyzing project...");

        Task<AnalysisResult> task = new Task<>() {
            @Override
            protected AnalysisResult call() {
                return analyzer.analyzeProject(selectedProjectPath);
            }
        };

        task.setOnSucceeded(event -> {
            AnalysisResult result = task.getValue();
            latestResult = result;
            rows.setAll(result.getClassMetrics());
            summaryArea.setText(result.buildSummary());
            statusLabel.setText("Completed: " + result.getClassCount() + " classes analyzed");
            analyzeButton.setDisable(false);
            renderCouplingGraph();
        });

        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            String message = ex == null ? "Unknown error" : ex.getMessage();
            statusLabel.setText("Failed");
            summaryArea.setText("Analysis failed:\n" + message);
            analyzeButton.setDisable(false);
            renderNoGraphMessage("Analysis failed. Fix the issue and run again.");
        });

        Thread worker = new Thread(task, "metrics-analysis-thread");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderCouplingGraph() {
        if (latestResult == null) {
            renderNoGraphMessage("Run an analysis to visualize coupling relations.");
            return;
        }

        boolean packageLevel = GRAPH_PACKAGE_LEVEL.equals(graphLevelCombo.getValue());
        List<DependencyRelation> relations = packageLevel
                ? latestResult.getPackageCouplings()
                : latestResult.getClassCouplings();

        if (relations.isEmpty()) {
            renderNoGraphMessage("No " + (packageLevel ? "package" : "class/file") + " coupling relations found.");
            return;
        }

        Set<String> edgeKeys = new LinkedHashSet<>();
        Set<String> allNodes = new LinkedHashSet<>();
        for (DependencyRelation relation : relations) {
            edgeKeys.add(edgeKey(relation.getSource(), relation.getTarget()));
            allNodes.add(relation.getSource());
            allNodes.add(relation.getTarget());
        }

        List<String> sortedNodes = new ArrayList<>(allNodes);
        sortedNodes.sort((a, b) -> {
            String[] partsA = a.split("\\.");
            String[] partsB = b.split("\\.");
            int minLen = Math.min(partsA.length, partsB.length);
            for (int i = 0; i < minLen; i++) {
                boolean aIsDir = i < partsA.length - 1 || packageLevel;
                boolean bIsDir = i < partsB.length - 1 || packageLevel;
                if (aIsDir != bIsDir) {
                    return aIsDir ? -1 : 1;
                }
                int cmp = partsA[i].compareTo(partsB[i]);
                if (cmp != 0) return cmp;
            }
            return Integer.compare(partsA.length, partsB.length);
        });

        SwingUtilities.invokeLater(() -> {
            mxGraph graph = new mxGraph();
            Object parent = graph.getDefaultParent();
            Map<String, Object> vertices = new LinkedHashMap<>();

            graph.getModel().beginUpdate();
            try {
                for (String node : sortedNodes) {
                    vertices.computeIfAbsent(node,
                            key -> graph.insertVertex(parent, null, shortLabel(key), 0, 0, 190, 55, NODE_STYLE));
                }

                for (DependencyRelation relation : relations) {
                    String forward = edgeKey(relation.getSource(), relation.getTarget());
                    String reverse = edgeKey(relation.getTarget(), relation.getSource());
                    boolean bidirectional = edgeKeys.contains(forward) && edgeKeys.contains(reverse);
                    
                    if (bidirectional && forward.compareTo(reverse) > 0) {
                        continue;
                    }
                    
                    String style = bidirectional ? EDGE_STYLE_BIDIRECTIONAL : EDGE_STYLE_NORMAL;
                    if (bidirectional) {
                        style = "startArrow=classic;" + style;
                    }

                    if (!bidirectional) {
                        int colorIndex = Math.abs(relation.getSource().hashCode()) % EDGE_COLORS.length;
                        style = style + "strokeColor=" + EDGE_COLORS[colorIndex] + ";";
                    }

                    graph.insertEdge(
                            parent,
                            null,
                            "",
                            vertices.get(relation.getSource()),
                            vertices.get(relation.getTarget()),
                            style
                    );
                }
            } finally {
                graph.getModel().endUpdate();
            }

            mxHierarchicalLayout layout = new mxHierarchicalLayout(graph);
            layout.setOrientation(javax.swing.SwingConstants.WEST);
            layout.setIntraCellSpacing(50.0);
            layout.setInterRankCellSpacing(100.0);
            layout.execute(parent);

            mxGraphComponent component = new mxGraphComponent(graph);
            component.setConnectable(false);
            component.setAutoExtend(true);
            component.setPanning(true);
            component.setToolTips(true);
            component.setOpaque(true);
            component.setBackground(java.awt.Color.WHITE);
            component.getViewport().setOpaque(true);
            component.getViewport().setBackground(java.awt.Color.WHITE);
            component.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    component.revalidate();
                    component.repaint();
                }
            });

            setSwingContent(component);
        });
    }

    private void renderNoGraphMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout());
            panel.setBackground(java.awt.Color.WHITE);
            panel.add(new javax.swing.JLabel(message, javax.swing.SwingConstants.CENTER), java.awt.BorderLayout.CENTER);
            Platform.runLater(() -> {
                graphNode.setContent(panel);
                graphNode.requestFocus();
            });
        });
    }

    private void setSwingContent(mxGraphComponent component) {
        Platform.runLater(() -> {
            graphNode.setContent(component);
            graphNode.requestFocus();
        });
        SwingUtilities.invokeLater(() -> {
            component.revalidate();
            component.refresh();
            component.repaint();
        });
    }

    private static String edgeKey(String source, String target) {
        return source + "->" + target;
    }

    private static String shortLabel(String text) {
        int idx = text.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= text.length()) {
            return text;
        }
        return text.substring(idx + 1);
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.US, "%.2f", value));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
