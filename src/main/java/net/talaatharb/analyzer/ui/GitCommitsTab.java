package net.talaatharb.analyzer.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GitCommitsTab {
    private static final int LIMIT = 50;

    private Path projectPath;
    private ObservableList<CommitInfo> commits;
    private Tab tab;
    private int offset = 0;
    private String currentBranch = "";

    @FXML
    private ComboBox<String> branchComboBox;
    @FXML
    private ListView<CommitInfo> commitListView;
    @FXML
    private Button loadMoreButton;
    @FXML
    private Label currentBranchLabel;
    @FXML
    private Button switchButton;
    @FXML
    private Button refreshButton;
    @FXML
    private Button diffButton;

    public Tab createTab(Path projectPath) {
        this.projectPath = projectPath;
        if (tab != null) {
            if (projectPath != null) {
                loadGitData();
            }
            return tab;
        }

        commits = FXCollections.observableArrayList();
        Parent content = loadContent();

        switchButton.setOnAction(_ -> {
            String selected = branchComboBox.getValue();
            if (selected != null && !selected.equals(currentBranch)) {
                switchBranch(selected);
            }
        });
        refreshButton.setOnAction(_ -> loadGitData());
        diffButton.setOnAction(_ -> showWorkingTreeDiff());

        commitListView.setItems(commits);
        commitListView.setCellFactory(_ -> new ListCell<>() {
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

                    if (getIndex() == getListView().getItems().size() - 1
                            && loadMoreButton.isVisible() && !loadMoreButton.isDisabled()) {
                        Platform.runLater(() -> loadCommits());
                    }
                }
            }
        });

        commitListView.setOnMouseClicked(this::handleCommitClick);
        commitListView.setOnScroll(_ -> {
            // Alternatively we use the Load More button for robust loading
        });

        loadMoreButton.setOnAction(_ -> loadCommits());
        loadMoreButton.setVisible(false);

        if (projectPath != null) {
            loadGitData();
        }

        tab = new Tab("Git Commits", content);
        tab.setClosable(false);
        return tab;
    }

    public void setProjectPath(Path path) {
        this.projectPath = path;
        loadGitData();
    }

    private Parent loadContent() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("GitCommitsTab.fxml"));
        loader.setController(this);
        try {
            return loader.load();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load GitCommitsTab.fxml", ex);
        }
    }

    private void handleCommitClick(MouseEvent event) {
        if (event.getClickCount() == 2) {
            CommitInfo selected = commitListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showDiffPopup(selected);
            }
        }
    }

    private boolean isGitRepository(File dir) {
        if (dir == null || !dir.exists()) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("git", "status").directory(dir).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadGitData() {
        if (branchComboBox == null || commitListView == null || loadMoreButton == null || currentBranchLabel == null) {
            return;
        }
        if (projectPath == null || !isGitRepository(projectPath.toFile())) {
            Platform.runLater(() -> {
                currentBranchLabel.setText("Not a Git repository");
                branchComboBox.getItems().clear();
                commits.clear();
                loadMoreButton.setVisible(false);
            });
            return;
        }

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
                        if (b.startsWith("remotes/origin/HEAD")) {
                            continue;
                        }
                        branches.add(b);
                    }
                }
                return branches;
            }
        };
        branchesTask.setOnSucceeded(_ -> {
            branchComboBox.setItems(FXCollections.observableArrayList(branchesTask.getValue()));
            currentBranchLabel.setText("Current Branch: " + currentBranch);
            branchComboBox.getSelectionModel().select(currentBranch);

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
        switchTask.setOnSucceeded(_ -> {
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
                Process p = new ProcessBuilder("git", "log", "--skip=" + offset, "-n", String.valueOf(LIMIT),
                        "--pretty=format:%h%x09%an%x09%ad%x09%s").directory(projectPath.toFile()).start();
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
        commitsTask.setOnSucceeded(_ -> {
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
        diffTask.setOnSucceeded(_ -> showDiffDialog("Diff for commit " + commit.hash, diffTask.getValue()));
        new Thread(diffTask).start();
    }

    private void showWorkingTreeDiff() {
        if (projectPath == null || !isGitRepository(projectPath.toFile())) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "No Git repository selected.");
            alert.showAndWait();
            return;
        }

        Task<String> diffTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                File gitRoot = resolveGitRoot(projectPath.toFile());
                String diff = runCommandAndCapture(Arrays.asList("git", "diff"), gitRoot);
                if (diff.isBlank()) {
                    return "No working tree changes.";
                }
                return diff;
            }
        };
        diffTask.setOnSucceeded(_ -> showDiffDialog("Working Tree Diff", diffTask.getValue()));
        diffTask.setOnFailed(_ -> {
            Throwable ex = diffTask.getException();
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Failed to load working tree diff." + (ex == null ? "" : "\n" + ex.getMessage()));
            alert.showAndWait();
        });
        new Thread(diffTask).start();
    }

    private File resolveGitRoot(File workingDir) throws Exception {
        String root = runCommandAndCapture(Arrays.asList("git", "rev-parse", "--show-toplevel"), workingDir).trim();
        if (root.isBlank()) {
            return workingDir;
        }
        return new File(root);
    }

    private String runCommandAndCapture(List<String> command, File workingDir) throws Exception {
        Process p = new ProcessBuilder(command).directory(workingDir).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        p.waitFor();
        return sb.toString();
    }

    private void showDiffDialog(String title, String diffContent) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);

        CodeArea codeArea = new CodeArea();
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.setEditable(false);
        codeArea.setWrapText(false);
        codeArea.replaceText(diffContent == null ? "" : diffContent);

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
