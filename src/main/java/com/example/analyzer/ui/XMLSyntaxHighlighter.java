package com.example.analyzer.ui;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

public class XMLSyntaxHighlighter {
    
    private static class Token {
        int start, end;
        String style;
        Token(int start, int end, String style) {
            this.start = start;
            this.end = end;
            this.style = style;
        }
    }

    public static StyleSpans<Collection<String>> computeHighlighting(String text) {
        if (text == null || text.isEmpty()) {
            return new StyleSpansBuilder<Collection<String>>().add(Collections.emptyList(), 0).create();
        }

        List<Token> tokens = new LinkedList<>();
        
        // Match: comments, CDATA, tags, and strings
        Pattern pattern = Pattern.compile(
            "<!--.*?-->|<\\!\\[CDATA\\[.*?\\]\\]>|<[^>]+>|\"[^\"]*\"|'[^']*'",
            Pattern.DOTALL
        );
        
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String match = matcher.group();
            String style = null;
            
            if (match.startsWith("<!--")) {
                style = "comment";
            } else if (match.startsWith("<![CDATA[")) {
                style = "string";
            } else if (match.startsWith("<")) {
                style = "tag";
            } else {
                style = "string";
            }
            
            tokens.add(new Token(matcher.start(), matcher.end(), style));
        }

        // Build spans
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastPos = 0;

        for (Token token : tokens) {
            if (token.start > lastPos) {
                spansBuilder.add(Collections.emptyList(), token.start - lastPos);
            }
            spansBuilder.add(Collections.singleton(token.style), token.end - token.start);
            lastPos = token.end;
        }

        if (lastPos < text.length()) {
            spansBuilder.add(Collections.emptyList(), text.length() - lastPos);
        }

        return spansBuilder.create();
    }
}
