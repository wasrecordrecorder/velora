package io.velora.internal.language;

import io.velora.api.language.TextEdit;

import java.util.*;

public final class Formatter {

    public static List<TextEdit> format(String content, String filePath) {
        String[] lines = content.split("\n", -1);
        StringBuilder formatted = new StringBuilder();
        int indent = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                formatted.append('\n');
                continue;
            }
            if (trimmed.startsWith("}")) indent = Math.max(0, indent - 1);
            formatted.append("    ".repeat(indent)).append(trimmed).append('\n');
            int opens = countChar(trimmed, '{');
            int closes = countChar(trimmed, '}');
            indent += opens - closes;
            if (indent < 0) indent = 0;
        }
        String result = formatted.toString();
        if (result.endsWith("\n") && !content.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.equals(content)) {
            return List.of(new TextEdit(filePath, 1, 1, countLines(content), 1, content));
        }
        return List.of(new TextEdit(filePath, 1, 1, countLines(content) + 1, 1, result));
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) count++;
        return count;
    }

    private static int countLines(String s) {
        int count = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') count++;
        return count;
    }
}
