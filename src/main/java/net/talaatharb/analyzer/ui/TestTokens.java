package net.talaatharb.analyzer.ui;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import org.fxmisc.richtext.model.StyleSpans;
public class TestTokens {
    public static void main(String[] args) throws Exception {
        String content = Files.readString(Paths.get("src/main/java/com/example/analyzer/MetricsAnalyzerApp.java"));
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        System.out.println("Normalized length: " + normalized.length());
        
        StyleSpans<Collection<String>> spans = JavaSyntaxHighlighter.computeHighlighting(normalized);
        System.out.println("Spans length: " + spans.length());
        
        if (spans.length() != normalized.length()) {
            System.out.println("MISMATCH!");
        } else {
            System.out.println("MATCH!");
        }
    }
}
