package com.example.analyzer.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.concurrent.Task;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GitCommitsTab {
    private Path projectPath;
    private ComboBox<String> branchComboBox;
    private ListView<CommitInfo> commitListView;
    private ObservableList<CommitInfo> commits;
    private Button loadMoreButton;
    private Label currentBranchLabel;
    
    private int offset = 0;
    private static final int LIMIT = 50;
    private String currentBranch = "";

    public Tab createTab(Path projectPath) {
        this.projectPath = projectPath;
        this.commits = FXCollections.observableArrayList();
        
        BorderPane content = new BorderPane();
        content.setPadding(new Insets(10));
        
        // Top Controls
        HBox topControls = new HBox(10);
        topControls.setPadding(new Insets(0, 0, 10, 0));
        topControls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        currentBranchLabel = new Label("Current Branch: None");
        currentBranchLabel.setStyle("-fx-font-weight: bold;");
        
        branchComboBox = new ComboBox<>();
        Button switchButton = new Button("Switch Branch");
        switchButton.setOnAction(e -> {
            String selected = branchComboBox.getValue();
            if (selected != null && !selected.equals(currentBranch)) {
                switchBranch(selected);
            }
        });
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> loadGitData());
        
        topControls.getChildren().addAll(currentBranchLabel, new Label("  |  Switch to:"), branchComboBox, switchButton, refreshButton);
        content.setTop(topControls);
        
        // Commits List
        commitListView = new ListView<>(commits);
        commitListView.setCellFactory(param -> new ListCell<CommitInfo>() {
            @Override
            protected void updateItem(CommitInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    VBox box = new VBox(5);
                    box.setPadding(new Insets(5));
                    Label hashLabel = new Label(item.hash + " - " + item.author + " on " + item.date);
                    hashLabel.setStyle("-fx-font-family: monospace; -fx-font-weight: bold;");
                    Label msgLabel = new Label(item.message);
                    msgLabel.setWrapText(true);
                    box.getChildren().addAll(hashLabel, msgLabel);
                    setGraphic(box);
                    
                    // Robust infinite scroll: trigger load when the last cell is being displayed
                    if (getIndex() == getListView().getItems().size() - 1 && loadMoreButton.isVisible() && !loadMoreButton.isDisabled()) {
                        Platform.runLater(() -> loadCommits());
                    }
                }
            }
        });
        
        commitListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                CommitInfo selected = commitListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    showDiffPopup(selected);
                }
            }
        });
        
        // Infinite scroll emulation with a scroll bar listener
        commitListView.setOnScroll(event -> {
            // Alternatively we use the Load More button for robust loading
        });
        
        VBox listContainer = new VBox(commitListView);
        VBox.setVgrow(commitListView, Priority.ALWAYS);
        
        loadMoreButton = new Button("Load More Commits");
        loadMoreButton.setMaxWidth(Double.MAX_VALUE);
        loadMoreButton.setOnAction(e -> loadCommits());
        loadMoreButton.setVisible(false);
        
        listContainer.getChildren().add(loadMoreButton);
        content.setCenter(listContainer);
        
        Label instructions = new Label("Double-click a commit to view its diff.");
        instructions.setPadding(new Insets(5, 0, 0, 0));
        content.setBottom(instructions);
        
        if (projectPath != null) {
            loadGitData();
        }
        
        Tab tab = new Tab("Git Commits", content);
        tab.setClosable(false);
        return tab;
    }
    
    public void setProjectPath(Path path) {
        this.projectPath = path;
        loadGitData();
    }
    
    private boolean isGitRepository(File dir) {
        if (dir == null || !dir.exists()) return false;
        try {
            Process p = new ProcessBuilder("git", "status").directory(dir).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadGitData() {
        if (projectPath == null || !isGitRepository(projectPath.toFile())) {
            Platform.runLater(() -> {
                currentBranchLabel.setText("Not a Git repository");
                branchComboBox.getItems().clear();
                commits.clear();
                loadMoreButton.setVisible(false);
            });
            return;
        }
        
        // Load branches
        Task<List<String>> branchesTask = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                List<String> branches = new ArrayList<>();
                Process p = new ProcessBuilder("git", "branch", "-a").directory(projectPath.toFile()).start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String b = line.substring(2).trim();
                        if (line.startsWith("* ")) {
                            currentBranch = b;
                        }
                        if (b.startsWith("remotes/origin/HEAD")) continue;
                        branches.add(b);
                    }
                }
                return branches;
            }
        };
        branchesTask.setOnSucceeded(e -> {
            branchComboBox.setItems(FXCollections.observableArrayList(branchesTask.getValue()));
            currentBranchLabel.setText("Current Branch: " + currentBranch);
            branchComboBox.getSelectionModel().select(currentBranch);
            
            // Reload commits from scratch
            offset = 0;
            commits.clear();
            loadCommits();
        });
        new Thread(branchesTask).start();
    }
    
    private void switchBranch(String branchName) {
        String checkoutTarget = branchName;
        if (branchName.startsWith("remotes/origin/")) {
            checkoutTarget = branchName.substring("remotes/origin/".length());
        }
        final String finalTarget = checkoutTarget;
        Task<Boolean> switchTask = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                Process p = new ProcessBuilder("git", "checkout", finalTarget).directory(projectPath.toFile()).start();
                return p.waitFor() == 0;
            }
        };
        switchTask.setOnSucceeded(e -> {
            if (switchTask.getValue()) {
                loadGitData();
            } else {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to switch branch.");
                alert.showAndWait();
            }
        });
        new Thread(switchTask).start();
    }
    
    private void loadCommits() {
        loadMoreButton.setDisable(true);
        Task<List<CommitInfo>> commitsTask = new Task<>() {
            @Override
            protected List<CommitInfo> call() throws Exception {
                List<CommitInfo> newCommits = new ArrayList<>();
                Process p = new ProcessBuilder("git", "log", "--skip=" + offset, "-n", String.valueOf(LIMIT), "--pretty=format:%h%x09%an%x09%ad%x09%s").directory(projectPath.toFile()).start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] parts = line.split("\t", 4);
                        if (parts.length == 4) {
                            newCommits.add(new CommitInfo(parts[0], parts[1], parts[2], parts[3]));
                        }
                    }
                }
                return newCommits;
            }
        };
        commitsTask.setOnSucceeded(e -> {
            List<CommitInfo> result = commitsTask.getValue();
            commits.addAll(result);
            offset += result.size();
            loadMoreButton.setDisable(false);
            loadMoreButton.setVisible(result.size() == LIMIT);
        });
        new Thread(commitsTask).start();
    }
    
    private void showDiffPopup(CommitInfo commit) {
        Task<String> diffTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                Process p = new ProcessBuilder("git", "show", commit.hash).directory(projectPath.toFile()).start();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                }
                return sb.toString();
            }
        };
        diffTask.setOnSucceeded(e -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("Diff for commit " + commit.hash);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dialog.setResizable(true);

            CodeArea codeArea = new CodeArea();
            codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
            codeArea.setEditable(false);
            codeArea.setWrapText(false);
            codeArea.replaceText(diffTask.getValue());

            String content = codeArea.getText();
            var spans = GitDiffSyntaxHighlighter.computeHighlighting(content);
            if (spans.length() == content.length()) {
                codeArea.setStyleSpans(0, spans);
            }

            DialogPane pane = dialog.getDialogPane();
            String cssResource = getClass().getResource("/syntax-highlighting.css").toExternalForm();
            pane.getStylesheets().add(cssResource);
            VirtualizedScrollPane<CodeArea> diffScrollPane = new VirtualizedScrollPane<>(codeArea);
            diffScrollPane.setPrefSize(1200, 800);
            pane.setMinSize(900, 600);
            pane.setPrefSize(1200, 800);
            pane.setContent(diffScrollPane);
            dialog.showAndWait();
        });
        new Thread(diffTask).start();
    }
    
    private static class CommitInfo {
        String hash;
        String author;
        String date;
        String message;
        
        CommitInfo(String hash, String author, String date, String message) {
            this.hash = hash;
            this.author = author;
            this.date = date;
            this.message = message;
        }
    }
}