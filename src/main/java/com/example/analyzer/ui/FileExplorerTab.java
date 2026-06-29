package com.example.analyzer.ui;

import javafx.collections.ObservableList;
import javafx.scene.control.Tab;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class FileExplorerTab {
    private final CodeArea codeArea;
    private final TreeView<File> fileTree;
    private Path rootPath;

    public FileExplorerTab() {
        this.fileTree = createFileTree();
        this.codeArea = createCodeArea();
    }

    public Tab createTab(Path projectPath) {
        this.rootPath = projectPath;
        
        BorderPane content = new BorderPane();
        
        VBox treeContainer = new VBox(fileTree);
        treeContainer.setPadding(new Insets(8));
        VBox.setVgrow(fileTree, Priority.ALWAYS);
        treeContainer.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 0 1 0 0;");
        treeContainer.setPrefWidth(250);
        
        VBox codeContainer = new VBox(codeArea);
        codeContainer.setPadding(new Insets(8));
        VBox.setVgrow(codeArea, Priority.ALWAYS);
        
        HBox.setHgrow(codeContainer, Priority.ALWAYS);
        HBox mainContent = new HBox(treeContainer, codeContainer);
        mainContent.setSpacing(0);
        
        content.setCenter(mainContent);
        
        // Apply CSS stylesheet for syntax highlighting
        String cssResource = getClass().getResource("/syntax-highlighting.css").toExternalForm();
        content.getStylesheets().add(cssResource);
        
        refreshFileTree(projectPath);
        
        Tab tab = new Tab("File Explorer", content);
        tab.setClosable(false);
        return tab;
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
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 12;");
        area.setEditable(false);
        area.setWrapText(false);
        return area;
    }

    private String currentFileName;

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
                    System.err.println("Span length mismatch for " + filename + ": expected " + content.length() + " but got " + spans.length());
                }
            } catch (Exception e) {
                System.err.println("Syntax highlighting error for " + filename + ": " + e.getMessage());
            }
            
            codeArea.moveTo(0);
        } catch (IOException e) {
            codeArea.clear();
            codeArea.appendText("Error reading file: " + e.getMessage());
        }
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
    }

    private static class FileTreeCell extends TreeCell<File> {
        @Override
        protected void updateItem(File file, boolean empty) {
            super.updateItem(file, empty);
            
            if (empty || file == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(file.getName());
            }
        }
    }
}
