package com.example.analyzer.ui;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

public class JavaSyntaxHighlighter {
    
    private static final String[] KEYWORDS = {
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "false", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "null", "package", "private", "protected", "public", "return",
        "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "true", "try", "void", "volatile", "while"
    };

    private static class Token {
        int start;
        int end;
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
        int i = 0;

        while (i < text.length()) {
            // Line comment
            if (i + 1 < text.length() && text.charAt(i) == '/' && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                if (end == -1) end = text.length();
                tokens.add(new Token(i, end, "comment"));
                i = end;
                continue;
            }

            // Block comment
            if (i + 1 < text.length() && text.charAt(i) == '/' && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                if (end == -1) end = text.length();
                else end += 2;
                tokens.add(new Token(i, end, "comment"));
                i = end;
                continue;
            }

            // String
            if (text.charAt(i) == '"') {
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != '"') {
                    if (text.charAt(end) == '\\') {
                        end++;
                    }
                    if (end < text.length()) {
                        end++;
                    }
                }
                if (end < text.length()) end++;
                tokens.add(new Token(i, end, "string"));
                i = end;
                continue;
            }

            // Char
            if (text.charAt(i) == '\'') {
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != '\'') {
                    if (text.charAt(end) == '\\') {
                        end++;
                    }
                    if (end < text.length()) {
                        end++;
                    }
                }
                if (end < text.length()) end++;
                tokens.add(new Token(i, end, "string"));
                i = end;
                continue;
            }

            // Number
            if (Character.isDigit(text.charAt(i))) {
                int end = i;
                while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
                if (end < text.length() && text.charAt(end) == '.') {
                    end++;
                    while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
                }
                if (end < text.length() && (text.charAt(end) == 'e' || text.charAt(end) == 'E')) {
                    end++;
                    if (end < text.length() && (text.charAt(end) == '+' || text.charAt(end) == '-')) end++;
                    while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
                }
                while (end < text.length() && (text.charAt(end) == 'L' || text.charAt(end) == 'l' || 
                       text.charAt(end) == 'f' || text.charAt(end) == 'F' || 
                       text.charAt(end) == 'd' || text.charAt(end) == 'D')) end++;
                
                tokens.add(new Token(i, end, "number"));
                i = end;
                continue;
            }

            // Identifier or keyword
            if (Character.isJavaIdentifierStart(text.charAt(i))) {
                int end = i;
                while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
                String word = text.substring(i, end);
                
                if (isKeyword(word)) {
                    tokens.add(new Token(i, end, "keyword"));
                } else if (text.charAt(i) == '@') {
                    tokens.add(new Token(i, end, "annotation"));
                }
                
                i = end;
                continue;
            }

            i++;
        }

        // Build spans from tokens
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastPos = 0;

        for (Token token : tokens) {
            // Add gap before token if exists
            if (token.start > lastPos) {
                spansBuilder.add(Collections.emptyList(), token.start - lastPos);
            }
            // Add styled token
            spansBuilder.add(Collections.singleton(token.style), token.end - token.start);
            lastPos = token.end;
        }

        // Add final gap
        if (lastPos < text.length()) {
            spansBuilder.add(Collections.emptyList(), text.length() - lastPos);
        }

        return spansBuilder.create();
    }

    private static boolean isKeyword(String token) {
        for (String kw : KEYWORDS) {
            if (kw.equals(token)) return true;
        }
        return false;
    }
}

