package com.example.analyzer.ui;

import org.fxmisc.richtext.model.StyleSpans;
import java.util.Collection;

public class SyntaxHighlighterFactory {
    
    public static StyleSpans<Collection<String>> highlight(String filename, String text) {
        String lower = filename.toLowerCase();
        
        if (lower.endsWith(".java")) {
            return JavaSyntaxHighlighter.computeHighlighting(text);
        } else if (lower.endsWith(".json")) {
            return JSONSyntaxHighlighter.computeHighlighting(text);
        } else if (lower.endsWith(".xml") || lower.endsWith(".pom")) {
            return XMLSyntaxHighlighter.computeHighlighting(text);
        } else if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return YAMLSyntaxHighlighter.computeHighlighting(text);
        } else if (lower.endsWith(".properties")) {
            return PropertiesSyntaxHighlighter.computeHighlighting(text);
        } else if (lower.endsWith(".diff") || lower.endsWith(".patch")) {
            return GitDiffSyntaxHighlighter.computeHighlighting(text);
        }
        
        // No syntax highlighting for unknown file types
        return null;
    }
}
