package io.velora.internal.language;

import io.velora.api.language.TextEdit;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Formatter {
    private Formatter() {}

    public static List<TextEdit> format(String content, String filePath) {
        String[] lines = content.split("\\R", -1);
        Map<Integer, LineBraces> braces = braces(content, filePath);
        StringBuilder formatted = new StringBuilder(content.length() + 32);
        int indent = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            LineBraces lineBraces = braces.getOrDefault(i + 1, LineBraces.EMPTY);
            int lineIndent = Math.max(0, indent - lineBraces.leadingCloses());
            if (!trimmed.isEmpty()) formatted.append("    ".repeat(lineIndent)).append(trimmed);
            if (i + 1 < lines.length) formatted.append('\n');
            indent = Math.max(0, lineIndent + lineBraces.opens() - Math.max(0, lineBraces.closes() - lineBraces.leadingCloses()));
        }
        String result = formatted.toString();
        if (result.equals(content)) return List.of();
        int endLine = lines.length;
        int endColumn = lines.length == 0 ? 1 : lines[lines.length - 1].length() + 1;
        return List.of(new TextEdit(filePath, 1, 1, endLine, endColumn, result));
    }

    private static Map<Integer, LineBraces> braces(String content, String filePath) {
        Map<Integer, MutableLineBraces> mutable = new HashMap<>();
        for (Token token : new Lexer(content, filePath).lex().tokens()) {
            if (token.isTrivia() || token.is(TokenType.EOF)) continue;
            MutableLineBraces line = mutable.computeIfAbsent(token.line(), ignored -> new MutableLineBraces());
            if (!line.seenNonClose && token.is(TokenType.RBRACE)) line.leadingCloses++;
            else line.seenNonClose = true;
            if (token.is(TokenType.LBRACE)) line.opens++;
            else if (token.is(TokenType.RBRACE)) line.closes++;
        }
        Map<Integer, LineBraces> result = new HashMap<>();
        for (var entry : mutable.entrySet()) {
            MutableLineBraces value = entry.getValue();
            result.put(entry.getKey(), new LineBraces(value.opens, value.closes, value.leadingCloses));
        }
        return result;
    }

    private record LineBraces(int opens, int closes, int leadingCloses) {
        private static final LineBraces EMPTY = new LineBraces(0, 0, 0);
    }

    private static final class MutableLineBraces {
        private int opens;
        private int closes;
        private int leadingCloses;
        private boolean seenNonClose;
    }
}
