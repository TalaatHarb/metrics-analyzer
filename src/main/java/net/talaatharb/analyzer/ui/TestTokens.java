package net.talaatharb.analyzer.ui;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import org.fxmisc.richtext.model.StyleSpans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class TestTokens {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestTokens.class);
    public static void main(String[] args) throws Exception {
        String content = Files.readString(Paths.get("src/main/java/com/example/analyzer/MetricsAnalyzerApp.java"));
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        LOGGER.info("Normalized length: {}", normalized.length());
        
        StyleSpans<Collection<String>> spans = JavaSyntaxHighlighter.computeHighlighting(normalized);
        LOGGER.info("Spans length: {}", spans.length());
        
        if (spans.length() != normalized.length()) {
            LOGGER.warn("MISMATCH!");
        } else {
            LOGGER.info("MATCH!");
        }
    }
}
