package net.talaatharb.analyzer.ui;
import org.fxmisc.richtext.CodeArea;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class TestCodeArea {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestCodeArea.class);
    public static void main(String[] args) throws Exception {
        new JFXPanel(); // Init JavaFX
        Platform.runLater(() -> {
            CodeArea codeArea = new CodeArea();
            String original = "public class Main {\r\n    public static void main(String[] args) {\r\n    }\r\n}";
            LOGGER.info("Original length: {}", original.length());
            codeArea.appendText(original);
            LOGGER.info("CodeArea length: {}", codeArea.getLength());
            
            String normalized = original.replace("\r\n", "\n").replace("\r", "\n");
            LOGGER.info("Normalized length: {}", normalized.length());
            codeArea.clear();
            codeArea.appendText(normalized);
            LOGGER.info("CodeArea normalized length: {}", codeArea.getLength());
            System.exit(0);
        });
    }
}
