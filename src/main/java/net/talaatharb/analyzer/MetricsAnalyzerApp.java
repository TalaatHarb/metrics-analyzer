package net.talaatharb.analyzer;

import net.talaatharb.analyzer.model.AnalysisResult;
import net.talaatharb.analyzer.model.ClassMetrics;
import net.talaatharb.analyzer.service.JavaSourceProjectAnalyzer;
import net.talaatharb.analyzer.service.MetricsAnalyzerService;
import net.talaatharb.analyzer.ui.CouplingGraphTab;
import net.talaatharb.analyzer.ui.FileExplorerTab;
import net.talaatharb.analyzer.ui.GitCommitsTab;
import net.talaatharb.analyzer.ui.MetricsTab;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class MetricsAnalyzerApp extends Application {
    private final MetricsAnalyzerService analyzer = new JavaSourceProjectAnalyzer();

    private Label selectedFolderLabel;
    private Label statusLabel;
    private FileExplorerTab fileExplorerTab;
    private GitCommitsTab gitCommitsTab;
    private MetricsTab metricsTab;
    private CouplingGraphTab couplingGraphTab;
    private TabPane mainTabPane;

    private Path selectedProjectPath;

    @Override
    public void start(Stage stage) {
        selectedFolderLabel = new Label("No folder selected");
        statusLabel = new Label("Ready (" + analyzer.getDisplayName() + ")");
        fileExplorerTab = new FileExplorerTab();
        gitCommitsTab = new GitCommitsTab();
        metricsTab = new MetricsTab(this::navigateToClassInFileExplorer);
        couplingGraphTab = new CouplingGraphTab();

        Button chooseButton = new Button("Choose Project Folder");
        Button analyzeButton = new Button("Analyze");
        analyzeButton.setDisable(true);

        chooseButton.setOnAction(_ -> {
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
                metricsTab.setProjectPath(selectedProjectPath);
                fileExplorerTab.setProjectPath(selectedProjectPath);
                gitCommitsTab.setProjectPath(selectedProjectPath);
            }
        });

        analyzeButton.setOnAction(_ -> runAnalysis(analyzeButton));

        HBox actions = new HBox(10, chooseButton, analyzeButton);
        VBox top = new VBox(
                8,
                actions,
                new Label("Project Folder:"),
                selectedFolderLabel,
                statusLabel
        );
        top.setPadding(new Insets(12));
        
        mainTabPane = new TabPane();
        mainTabPane.getTabs().add(metricsTab.createTab());
        mainTabPane.getTabs().add(couplingGraphTab.createTab());
        mainTabPane.getTabs().add(fileExplorerTab.createTab(selectedProjectPath != null ? selectedProjectPath : new File(".").toPath()));
        mainTabPane.getTabs().add(gitCommitsTab.createTab(selectedProjectPath != null ? selectedProjectPath : new File(".").toPath()));

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(mainTabPane);

        Scene scene = new Scene(root, 1240, 760);
        stage.setTitle("Java Metrics Analyzer");
        stage.setScene(scene);
        stage.show();
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
        metricsTab.clearForAnalysis();
        couplingGraphTab.showPlaceholder("Analyzing project...");

        Task<AnalysisResult> task = new Task<>() {
            @Override
            protected AnalysisResult call() {
                return analyzer.analyzeProject(selectedProjectPath);
            }
        };

        task.setOnSucceeded(_ -> {
            AnalysisResult result = task.getValue();
            metricsTab.showAnalysisResult(result);
            statusLabel.setText("Completed: " + result.getClassCount() + " classes analyzed");
            analyzeButton.setDisable(false);
            couplingGraphTab.setAnalysisResult(result);
        });

        task.setOnFailed(_ -> {
            Throwable ex = task.getException();
            String message = ex == null ? "Unknown error" : ex.getMessage();
            statusLabel.setText("Failed");
            metricsTab.showSummaryMessage("Analysis failed:\n" + message);
            analyzeButton.setDisable(false);
            couplingGraphTab.showPlaceholder("Analysis failed. Fix the issue and run again.");
        });

        Thread worker = new Thread(task, "metrics-analysis-thread");
        worker.setDaemon(true);
        worker.start();
    }

    private void navigateToClassInFileExplorer(ClassMetrics metric) {
        if (selectedProjectPath == null || metric == null) {
            return;
        }

        // Convert package name to path (e.g., "com.example" -> "com/example")
        String packagePath = metric.getPackageName().replace('.', File.separatorChar);
        String className = metric.getClassName();
        // Handle inner classes (e.g., "OuterClass$InnerClass" -> "OuterClass")
        String simpleClassName = className.contains("$") ? className.split("\\$")[0] : className;
        
        Path filePath = selectedProjectPath
                .resolve("src")
                .resolve("main")
                .resolve("java")
                .resolve(packagePath)
                .resolve(simpleClassName + ".java");

        if (Files.isRegularFile(filePath)) {
            fileExplorerTab.navigateToFile(filePath);
            switchToFileExplorerTab();
        } else {
            // Try alternative paths (test sources, etc.)
            Path testPath = selectedProjectPath
                    .resolve("src")
                    .resolve("test")
                    .resolve("java")
                    .resolve(packagePath)
                    .resolve(simpleClassName + ".java");
            
            if (Files.isRegularFile(testPath)) {
                fileExplorerTab.navigateToFile(testPath);
                switchToFileExplorerTab();
            } else {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("File Not Found");
                alert.setHeaderText(null);
                alert.setContentText("Could not find source file for class: " + metric.getClassName());
                alert.showAndWait();
            }
        }
    }

    private void switchToFileExplorerTab() {
        if (mainTabPane != null) {
            // The File Explorer tab is the 3rd tab (index 2)
            if (mainTabPane.getTabs().size() > 2) {
                mainTabPane.getSelectionModel().select(2);
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
