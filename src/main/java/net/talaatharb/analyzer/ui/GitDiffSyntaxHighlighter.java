package net.talaatharb.analyzer.ui;

import java.util.Collection;
import java.util.Collections;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

public class GitDiffSyntaxHighlighter {

    public static StyleSpans<Collection<String>> computeHighlighting(String text) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();

        if (text == null || text.isEmpty()) {
            spansBuilder.add(Collections.emptyList(), 0);
            return spansBuilder.create();
        }

        int start = 0;
        while (start < text.length()) {
            int lineEnd = text.indexOf('\n', start);
            if (lineEnd == -1) {
                lineEnd = text.length();
            } else {
                lineEnd += 1; // include '\n' so full line is styled
            }

            String line = text.substring(start, lineEnd);
            String style = styleForLine(line);
            if (style == null) {
                spansBuilder.add(Collections.emptyList(), line.length());
            } else {
                spansBuilder.add(Collections.singleton(style), line.length());
            }

            start = lineEnd;
        }

        return spansBuilder.create();
    }

    private static String styleForLine(String line) {
        if (line.startsWith("diff --")) {
            return "git-diff-header";
        }
        if (line.startsWith("@@")) {
            return "git-diff-hunk";
        }
        if (line.startsWith("+") && !line.startsWith("+++")) {
            return "git-diff-added";
        }
        if (line.startsWith("-") && !line.startsWith("---")) {
            return "git-diff-removed";
        }
        return null;
    }
}
