package net.talaatharb.analyzer.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import net.talaatharb.analyzer.model.AnalysisResult;
import net.talaatharb.analyzer.model.ClassMetrics;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MetricsTab {
    private final ObservableList<ClassMetrics> rows = FXCollections.observableArrayList();
    private final Consumer<ClassMetrics> classNavigationHandler;

    @FXML
    private TableView<ClassMetrics> table;
    @FXML
    private TextArea summaryArea;
    @FXML
    private Button exportMetricsButton;
    @FXML
    private VBox tableContainer;
    private Path projectPath;
    private HealthSnapshot previousHealthSnapshot;
    private Tab tab;

    public MetricsTab(Consumer<ClassMetrics> classNavigationHandler) {
        this.classNavigationHandler = classNavigationHandler;
    }

    public Tab createTab() {
        if (tab != null) {
            return tab;
        }

        Parent content = loadContent();
        table = createTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        tableContainer.getChildren().add(table);

        exportMetricsButton.setDisable(true);
        exportMetricsButton.setOnAction(_ -> exportMetricsAsCsv());

        rows.addListener((javafx.collections.ListChangeListener<? super ClassMetrics>) _ ->
                exportMetricsButton.setDisable(rows.isEmpty()));

        tab = new Tab("Metrics", content);
        tab.setClosable(false);
        return tab;
    }

    private Parent loadContent() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("MetricsTab.fxml"));
        loader.setController(this);
        try {
            return loader.load();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load MetricsTab.fxml", ex);
        }
    }

    public void setProjectPath(Path projectPath) {
        this.projectPath = projectPath;
    }

    public void clearForAnalysis() {
        if (summaryArea != null) {
            summaryArea.clear();
        }
        rows.clear();
    }

    public void showAnalysisResult(AnalysisResult result) {
        rows.setAll(result.getClassMetrics());
        if (summaryArea != null) {
            summaryArea.setText(result.buildSummary() + System.lineSeparator() + System.lineSeparator()
                    + buildProjectHealthDashboard(result));
        }
        previousHealthSnapshot = HealthSnapshot.from(result);
    }

    public void showSummaryMessage(String message) {
        if (summaryArea != null) {
            summaryArea.setText(message == null ? "" : message);
        }
    }

    public static double debtScore(ClassMetrics cm) {
        double cc = cm.getCyclomaticComplexity();
        double mi = Math.min(100.0, Math.max(0.0, cm.getMaintainabilityIndex()));
        double coupling = cm.getEfferentCoupling();
        return cc * (1.0 - mi / 100.0) * (1.0 + coupling / 10.0);
    }

    private TableView<ClassMetrics> createTable() {
        TableView<ClassMetrics> tv = new TableView<>(rows);
        tv.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

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

        TableColumn<ClassMetrics, Number> debtCol = new TableColumn<>("Debt Score");
        debtCol.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(round(debtScore(v.getValue()))));
        debtCol.setSortType(TableColumn.SortType.DESCENDING);
        Tooltip debtTip = new Tooltip(
                "Debt Score = CC × (1 − MI/100) × (1 + Coupling/10)\n"
                        + "Red > 10 (high), Orange > 5 (medium), Green ≤ 5 (low)");
        Label debtHeader = new Label("Debt Score");
        debtHeader.setTooltip(debtTip);
        debtCol.setGraphic(debtHeader);
        debtCol.setText("");

        tv.getColumns().addAll(List.of(classCol, pkgCol, locCol, methodCol, fieldCol,
                couplingCol, lcomCol, ccCol, wmcCol, rfcCol, miCol, debtCol));

        tv.setRowFactory(_ -> {
            TableRow<ClassMetrics> row = new TableRow<>() {
                @Override
                protected void updateItem(ClassMetrics item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setStyle("");
                    } else {
                        double score = debtScore(item);
                        if (score > 10.0) {
                            setStyle("-fx-background-color: #fee2e2;");
                        } else if (score > 5.0) {
                            setStyle("-fx-background-color: #fef3c7;");
                        } else if (score > 0.0) {
                            setStyle("-fx-background-color: #dcfce7;");
                        } else {
                            setStyle("");
                        }
                    }
                }
            };

            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && projectPath != null && classNavigationHandler != null) {
                    ClassMetrics metric = row.getItem();
                    if (metric != null) {
                        classNavigationHandler.accept(metric);
                    }
                }
            });

            return row;
        });

        tv.getSortOrder().add(debtCol);
        return tv;
    }

    private String buildProjectHealthDashboard(AnalysisResult result) {
        List<ClassMetrics> metrics = result.getClassMetrics();
        if (metrics.isEmpty()) {
            return "Project Health Dashboard\nNo class metrics available.";
        }

        long highDebt = metrics.stream().filter(cm -> debtScore(cm) > 10.0).count();
        long mediumDebt = metrics.stream().filter(cm -> debtScore(cm) > 5.0 && debtScore(cm) <= 10.0).count();
        long lowDebt = metrics.stream().filter(cm -> debtScore(cm) > 0.0 && debtScore(cm) <= 5.0).count();
        double avgDebt = metrics.stream().mapToDouble(MetricsTab::debtScore).average().orElse(0.0);
        double maxDebt = metrics.stream().mapToDouble(MetricsTab::debtScore).max().orElse(0.0);

        List<ClassMetrics> hotspots = metrics.stream()
                .sorted(Comparator.comparingDouble(MetricsTab::debtScore).reversed())
                .limit(5)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("Project Health Dashboard").append(System.lineSeparator());
        sb.append("Debt Categories: ")
                .append("High=").append(highDebt)
                .append(", Medium=").append(mediumDebt)
                .append(", Low=").append(lowDebt)
                .append(System.lineSeparator());
        sb.append("Debt Score: avg=").append(format2(avgDebt))
                .append(", max=").append(format2(maxDebt))
                .append(System.lineSeparator());
        sb.append("Trend: ").append(buildTrendSummary(avgDebt, highDebt)).append(System.lineSeparator());
        sb.append("Top Hotspots:").append(System.lineSeparator());
        for (int i = 0; i < hotspots.size(); i++) {
            ClassMetrics cm = hotspots.get(i);
            sb.append(i + 1).append(". ")
                    .append(cm.getClassName())
                    .append("  debt=").append(format2(debtScore(cm)))
                    .append(", CC=").append(cm.getCyclomaticComplexity())
                    .append(", MI=").append(format2(cm.getMaintainabilityIndex()))
                    .append(", Coupling=").append(cm.getEfferentCoupling())
                    .append(System.lineSeparator());
        }
        return sb.toString().trim();
    }

    private String buildTrendSummary(double avgDebt, long highDebtCount) {
        if (previousHealthSnapshot == null) {
            return "Baseline established (run analysis again to see trend).";
        }

        double debtDelta = avgDebt - previousHealthSnapshot.averageDebt;
        long highDebtDelta = highDebtCount - previousHealthSnapshot.highDebtCount;
        String debtDirection = debtDelta > 0.01 ? "up" : debtDelta < -0.01 ? "down" : "stable";
        String highDirection = highDebtDelta > 0 ? "up" : highDebtDelta < 0 ? "down" : "stable";

        return "Avg debt " + debtDirection + " (" + signedFormat(debtDelta) + "), "
                + "high-debt hotspots " + highDirection + " (" + signedLong(highDebtDelta) + ").";
    }

    private String format2(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String signedFormat(double value) {
        return String.format(Locale.US, "%+.2f", value);
    }

    private String signedLong(long value) {
        return value >= 0 ? "+" + value : Long.toString(value);
    }

    private void exportMetricsAsCsv() {
        if (rows.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("No Metrics to Export");
            alert.setHeaderText(null);
            alert.setContentText("No class metrics available to export.");
            alert.showAndWait();
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Metrics as CSV");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        fileChooser.setInitialFileName("metrics-" + System.currentTimeMillis() + ".csv");
        if (projectPath != null && Files.isDirectory(projectPath)) {
            fileChooser.setInitialDirectory(projectPath.toFile());
        }

        File selectedFile = fileChooser.showSaveDialog(
                table.getScene() == null ? null : table.getScene().getWindow());
        if (selectedFile == null) {
            return;
        }

        try {
            ClassMetricsCsvExporter.export(selectedFile.toPath(), new ArrayList<>(rows));
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Export Complete");
            alert.setHeaderText(null);
            alert.setContentText("Metrics exported to " + selectedFile.getName() + ".");
            alert.showAndWait();
        } catch (IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Export Failed");
            alert.setHeaderText(null);
            alert.setContentText("Unable to export metrics to CSV: " + ex.getMessage());
            alert.showAndWait();
        }
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.US, "%.2f", value));
    }

    private static final class HealthSnapshot {
        private final double averageDebt;
        private final long highDebtCount;

        private HealthSnapshot(double averageDebt, long highDebtCount) {
            this.averageDebt = averageDebt;
            this.highDebtCount = highDebtCount;
        }

        private static HealthSnapshot from(AnalysisResult result) {
            List<ClassMetrics> classes = result.getClassMetrics();
            double avgDebt = classes.stream().mapToDouble(MetricsTab::debtScore).average().orElse(0.0);
            long highDebt = classes.stream().filter(cm -> debtScore(cm) > 10.0).count();
            return new HealthSnapshot(avgDebt, highDebt);
        }
    }
}
