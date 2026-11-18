package io.velora.internal.language;

import io.velora.api.language.TextEdit;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Formatter {
    private Formatter() {}

    public static List<TextEdit> format(String content, String filePath) {
        String[] lines = content.split("\\R", -1);
        List<Token> allTokens = new Lexer(content, filePath).lex().tokens();
        Map<Integer, LineBraces> braces = braces(allTokens);
        Set<Integer> preserveLines = multilineInteriorLines(allTokens);
        StringBuilder formatted = new StringBuilder(content.length() + 32);
        int indent = 0;
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            if (preserveLines.contains(lineNo)) {
                formatted.append(lines[i].replace("\r", ""));
                if (i + 1 < lines.length) formatted.append('\n');
                continue;
            }
            String trimmed = lines[i].trim();
            LineBraces lineBraces = braces.getOrDefault(lineNo, LineBraces.EMPTY);
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

    private static Set<Integer> multilineInteriorLines(List<Token> tokens) {
        Set<Integer> lines = new java.util.HashSet<>();
        for (Token token : tokens) {
            if (!(token.is(TokenType.COMMENT) || token.is(TokenType.STRING) || token.is(TokenType.STRING_INTERP))) continue;
            String text = token.text();
            int extraLines = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    extraLines++;
                    lines.add(token.line() + extraLines);
                }
            }
        }
        return lines;
    }

    private static Map<Integer, LineBraces> braces(List<Token> tokens) {
        Map<Integer, MutableLineBraces> mutable = new HashMap<>();
        for (Token token : tokens) {
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
