package net.talaatharb.analyzer.ui;
import org.fxmisc.richtext.CodeArea;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
public class TestCodeArea {
    public static void main(String[] args) throws Exception {
        new JFXPanel(); // Init JavaFX
        Platform.runLater(() -> {
            CodeArea codeArea = new CodeArea();
            String original = "public class Main {\r\n    public static void main(String[] args) {\r\n    }\r\n}";
            System.out.println("Original length: " + original.length());
            codeArea.appendText(original);
            System.out.println("CodeArea length: " + codeArea.getLength());
            
            String normalized = original.replace("\r\n", "\n").replace("\r", "\n");
            System.out.println("Normalized length: " + normalized.length());
            codeArea.clear();
            codeArea.appendText(normalized);
            System.out.println("CodeArea normalized length: " + codeArea.getLength());
            System.exit(0);
        });
    }
}
