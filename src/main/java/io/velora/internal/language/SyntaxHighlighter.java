package io.velora.internal.language;

import io.velora.api.language.SyntaxToken;
import io.velora.internal.lexer.LexerResult;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.*;

public final class SyntaxHighlighter {

    public static List<SyntaxToken> highlight(LexerResult result) {
        List<SyntaxToken> tokens = new ArrayList<>();
        for (Token t : result.tokens()) {
            SyntaxToken.TokenType type = mapType(t.type());
            tokens.add(new SyntaxToken(type, t.text(), t.line(), t.column() + 1, t.text().length()));
        }
        return tokens;
    }

    private static final Set<String> JS_KEYWORDS = Set.of(
            "function", "let", "const", "var", "return", "if", "else", "for", "while", "do",
            "switch", "case", "default", "break", "continue", "import", "export", "from", "as",
            "class", "extends", "new", "this", "typeof", "instanceof", "try", "catch", "finally",
            "throw", "async", "await", "yield"
    );

    private static final Set<String> LUA_KEYWORDS = Set.of(
            "and", "break", "do", "else", "elseif", "end", "for", "function", "goto", "if",
            "in", "local", "not", "or", "repeat", "return", "then", "until", "while"
    );

    public static List<SyntaxToken> highlightJs(String content) {
        return tokenizeGeneric(content, JS_KEYWORDS, Set.of("true", "false", "null", "undefined"), true);
    }

    public static List<SyntaxToken> highlightLua(String content) {
        return tokenizeGeneric(content, LUA_KEYWORDS, Set.of("true", "false", "nil"), false);
    }

    private static List<SyntaxToken> tokenizeGeneric(String content, Set<String> keywords, Set<String> booleans, boolean isJs) {
        List<SyntaxToken> tokens = new ArrayList<>();
        int len = content.length();
        int line = 1;
        int col = 1;
        int i = 0;
        while (i < len) {
            char c = content.charAt(i);
            if (c == '\n') {
                line++;
                col = 1;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                int startCol = col;
                int start = i;
                while (i < len && Character.isWhitespace(content.charAt(i)) && content.charAt(i) != '\n') {
                    i++;
                    col++;
                }
                tokens.add(new SyntaxToken(SyntaxToken.TokenType.WHITESPACE, content.substring(start, i), line, startCol, i - start));
                continue;
            }
            if (isJs && c == '/' && i + 1 < len && content.charAt(i + 1) == '/') {
                int startCol = col;
                int start = i;
                while (i < len && content.charAt(i) != '\n') {
                    i++;
                    col++;
                }
                tokens.add(new SyntaxToken(SyntaxToken.TokenType.COMMENT, content.substring(start, i), line, startCol, i - start));
                continue;
            }
            if (isJs && c == '/' && i + 1 < len && content.charAt(i + 1) == '*') {
                int startCol = col;
                int startLine = line;
                int start = i;
                i += 2;
                col += 2;
                while (i + 1 < len && !(content.charAt(i) == '*' && content.charAt(i + 1) == '/')) {
                    if (content.charAt(i) == '\n') { line++; col = 1; } else col++;
                    i++;
                }
                if (i + 1 < len) { i += 2; col += 2; }
                tokens.add(new SyntaxToken(SyntaxToken.TokenType.COMMENT, content.substring(start, i), startLine, startCol, i - start));
                continue;
            }
            if (!isJs && c == '-' && i + 1 < len && content.charAt(i + 1) == '-') {
                int startCol = col;
                int start = i;
                while (i < len && content.charAt(i) != '\n') {
                    i++;
                    col++;
                }
                tokens.add(new SyntaxToken(SyntaxToken.TokenType.COMMENT, content.substring(start, i), line, startCol, i - start));
                continue;
            }
            if (c == '"' || c == '\'' || (isJs && c == '`')) {
                char quote = c;
                int startCol = col;
                int startLine = line;
                int start = i++;
                col++;
                boolean esc = false;
                while (i < len) {
                    char ch = content.charAt(i);
                    if (ch == '\n') { line++; col = 1; } else col++;
                    i++;
                    if (!esc && ch == quote) break;
                    esc = !esc && ch == '\\';
                }
                tokens.add(new SyntaxToken(SyntaxToken.TokenType.STRING, content.substring(start, i), startLine, startCol, i - start));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(content.charAt(i + 1)))) {
                int startCol = col;
                int start = i;
                if (c == '0' && i + 1 < len && (content.charAt(i + 1) == 'x' || content.charAt(i + 1) == 'X')) {
                    i += 2;
                    col += 2;
                    while (i < len && "0123456789abcdefABCDEF".indexOf(content.charAt(i)) >= 0) { i++; col++; }
                } else {
                    while (i < len && (Character.isDigit(content.charAt(i)) || content.charAt(i) == '.')) { i++; col++; }
                }
                tokens.add(new SyntaxToken(SyntaxToken.TokenType.NUMBER, content.substring(start, i), line, startCol, i - start));
                continue;
            }
            if (Character.isLetter(c) || c == '_' || (isJs && c == '$')) {
                int startCol = col;
                int start = i;
                while (i < len && (Character.isLetterOrDigit(content.charAt(i)) || content.charAt(i) == '_' || (isJs && content.charAt(i) == '$'))) {
                    i++;
                    col++;
                }
                String word = content.substring(start, i);
                SyntaxToken.TokenType tt = keywords.contains(word) ? SyntaxToken.TokenType.KEYWORD
                        : booleans.contains(word) ? SyntaxToken.TokenType.BOOLEAN
                        : SyntaxToken.TokenType.IDENTIFIER;
                tokens.add(new SyntaxToken(tt, word, line, startCol, i - start));
                continue;
            }
            int startCol = col;
            i++;
            col++;
            String op = String.valueOf(c);
            SyntaxToken.TokenType tt = "{}()[]:;,".indexOf(c) >= 0 ? SyntaxToken.TokenType.PUNCTUATION : SyntaxToken.TokenType.OPERATOR;
            tokens.add(new SyntaxToken(tt, op, line, startCol, 1));
        }
        return tokens;
    }

    private static SyntaxToken.TokenType mapType(TokenType tt) {
        if (tt == null) return SyntaxToken.TokenType.ERROR;
        if (tt == TokenType.IDENTIFIER) return SyntaxToken.TokenType.IDENTIFIER;
        if (tt == TokenType.STRING || tt == TokenType.STRING_INTERP) return SyntaxToken.TokenType.STRING;
        if (tt == TokenType.INTEGER || tt == TokenType.LONG_LITERAL
                || tt == TokenType.FLOAT_LITERAL || tt == TokenType.DOUBLE_LITERAL) return SyntaxToken.TokenType.NUMBER;
        if (tt == TokenType.BOOLEAN || tt == TokenType.KW_TRUE || tt == TokenType.KW_FALSE) return SyntaxToken.TokenType.BOOLEAN;
        if (tt == TokenType.NULL || tt == TokenType.KW_NULL) return SyntaxToken.TokenType.BOOLEAN;
        if (tt == TokenType.ANNOTATION || tt == TokenType.AT) return SyntaxToken.TokenType.ANNOTATION;
        if (tt == TokenType.HASH) return SyntaxToken.TokenType.KEYWORD;
        if (tt == TokenType.LBRACE || tt == TokenType.RBRACE || tt == TokenType.LPAREN || tt == TokenType.RPAREN
                || tt == TokenType.LBRACKET || tt == TokenType.RBRACKET || tt == TokenType.COMMA
                || tt == TokenType.DOT || tt == TokenType.COLON || tt == TokenType.SEMICOLON
                || tt == TokenType.ARROW || tt == TokenType.COLON_COLON) return SyntaxToken.TokenType.PUNCTUATION;
        if (tt == TokenType.PLUS || tt == TokenType.MINUS || tt == TokenType.STAR || tt == TokenType.SLASH
                || tt == TokenType.PERCENT || tt == TokenType.EQ || tt == TokenType.EQ_EQ
                || tt == TokenType.BANG || tt == TokenType.BANG_EQ || tt == TokenType.LT || tt == TokenType.LE
                || tt == TokenType.GT || tt == TokenType.GE || tt == TokenType.AND_AND || tt == TokenType.OR_OR
                || tt == TokenType.QUESTION || tt == TokenType.INCREMENT || tt == TokenType.DECREMENT
                || tt == TokenType.RANGE || tt == TokenType.PLUS_EQ || tt == TokenType.MINUS_EQ
                || tt == TokenType.STAR_EQ || tt == TokenType.SLASH_EQ || tt == TokenType.PERCENT_EQ) return SyntaxToken.TokenType.OPERATOR;
        if (tt.isKeyword()) return SyntaxToken.TokenType.KEYWORD;
        if (tt == TokenType.COMMENT) return SyntaxToken.TokenType.COMMENT;
        if (tt == TokenType.NEWLINE) return SyntaxToken.TokenType.NEWLINE;
        if (tt == TokenType.WHITESPACE) return SyntaxToken.TokenType.WHITESPACE;
        return SyntaxToken.TokenType.ERROR;
    }
}
