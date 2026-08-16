package io.velora.internal.lexer;

import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.compiler.DiagnosticSeverity;
import io.velora.api.compiler.SourceRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lexer for the Velora Script language (.vls).
 *
 * <p>Produces a token stream including trivia (whitespace, comments, newlines).
 * The parser decides which trivia to skip. Annotations are emitted as a single
 * {@link TokenType#ANNOTATION} token whose text includes the leading {@code @}.
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("script", TokenType.KW_SCRIPT),
            Map.entry("import", TokenType.KW_IMPORT),
            Map.entry("async", TokenType.KW_ASYNC),
            Map.entry("static", TokenType.KW_STATIC),
            Map.entry("if", TokenType.KW_IF),
            Map.entry("else", TokenType.KW_ELSE),
            Map.entry("while", TokenType.KW_WHILE),
            Map.entry("for", TokenType.KW_FOR),
            Map.entry("when", TokenType.KW_WHEN),
            Map.entry("return", TokenType.KW_RETURN),
            Map.entry("is", TokenType.KW_IS),
            Map.entry("in", TokenType.KW_IN),
            Map.entry("spawn", TokenType.KW_SPAWN),
            Map.entry("true", TokenType.KW_TRUE),
            Map.entry("false", TokenType.KW_FALSE),
            Map.entry("null", TokenType.KW_NULL)
    );

    private final String source;
    private final String filePath;
    private final int length;
    private int pos;
    private int line;
    private int column;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public Lexer(String source, String filePath) {
        this.source = source;
        this.filePath = filePath;
        this.length = source.length();
        this.pos = 0;
        this.line = 1;
        this.column = 0;
    }

    public LexerResult lex() {
        while (pos < length) {
            char c = source.charAt(pos);
            if (c == '\n') {
                emit(TokenType.NEWLINE, "\n");
                line++;
                column = 0;
                continue;
            }
            if (c == '\r') {
                emit(TokenType.NEWLINE, peek(1) == '\n' ? "\r\n" : "\r");
                line++;
                column = 0;
                continue;
            }
            if (c == ' ' || c == '\t') {
                int start = pos;
                while (pos < length && (source.charAt(pos) == ' ' || source.charAt(pos) == '\t')) {
                    pos++;
                    column++;
                }
                tokens.add(new Token(TokenType.WHITESPACE, source.substring(start, pos), line, column - (pos - start), start));
                continue;
            }
            if (c == '/' && peek(1) == '/') {
                lexLineComment();
                continue;
            }
            if (c == '/' && peek(1) == '*') {
                lexBlockComment();
                continue;
            }
            if (c == '@') {
                lexAnnotation();
                continue;
            }
            if (c == '#') {
                emit(TokenType.HASH, "#");
                continue;
            }
            if (c == '\\' && pos + 1 < length) {
                char next = source.charAt(pos + 1);
                if (next == 'n') {
                    emit(TokenType.NEWLINE, "\\n");
                    line++;
                    column = 1;
                    continue;
                }
                if (next == 't') {
                    int start = pos;
                    pos += 2;
                    column += 2;
                    tokens.add(new Token(TokenType.WHITESPACE, source.substring(start, pos), line, column - 2, start));
                    continue;
                }
                if (next == '\\') {
                    pos += 2;
                    column += 2;
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                lexString(c);
                continue;
            }
            if (Character.isDigit(c)) {
                lexNumber();
                continue;
            }
            if (c == '.' && peek(1) != '.' && pos + 1 < length && Character.isDigit(peek(1))) {
                lexNumber();
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                lexIdentifierOrKeyword();
                continue;
            }
            lexOperatorOrPunctuation();
        }
        tokens.add(new Token(TokenType.EOF, "", line, column, pos));
        return new LexerResult(List.copyOf(tokens), List.copyOf(diagnostics));
    }

    private void lexLineComment() {
        int start = pos;
        int startCol = column;
        while (pos < length && source.charAt(pos) != '\n') {
            pos++;
            column++;
        }
        tokens.add(new Token(TokenType.COMMENT, source.substring(start, pos), line, startCol, start));
    }

    private void lexBlockComment() {
        int start = pos;
        int startCol = column;
        int startLine = line;
        pos += 2;
        column += 2;
        while (pos < length) {
            char c = source.charAt(pos);
            if (c == '*' && peek(1) == '/') {
                pos += 2;
                column += 2;
                tokens.add(new Token(TokenType.COMMENT, source.substring(start, pos), startLine, startCol, start));
                return;
            }
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            pos++;
        }
        error(DiagnosticCode.LEXER_UNTERMINATED_STRING, "Unterminated block comment", startLine, startCol);
        tokens.add(new Token(TokenType.COMMENT, source.substring(start, pos), startLine, startCol, start));
    }

    private void lexAnnotation() {
        int start = pos;
        int startCol = column;
        pos++; // @
        column++;
        while (pos < length && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_')) {
            pos++;
            column++;
        }
        tokens.add(new Token(TokenType.ANNOTATION, source.substring(start, pos), line, startCol, start));
    }

    private void lexString(char quote) {
        int start = pos;
        int startLine = line;
        int startCol = column;
        pos++; // opening quote
        column++;
        StringBuilder sb = new StringBuilder();
        boolean hasInterpolation = false;
        boolean closed = false;
        while (pos < length) {
            char c = source.charAt(pos);
            if (c == quote) {
                pos++;
                column++;
                closed = true;
                break;
            }
            if (c == '\n') {
                error(DiagnosticCode.LEXER_UNTERMINATED_STRING, "Unterminated string literal", startLine, startCol);
                break;
            }
            if (c == '\\') {
                pos++;
                column++;
                if (pos < length) {
                    char esc = source.charAt(pos);
                    switch (esc) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '\\' -> sb.append('\\');
                        case '"' -> sb.append('"');
                        case '\'' -> sb.append('\'');
                        case '$' -> sb.append('$');
                        case '0' -> sb.append('\0');
                        default -> sb.append(esc);
                    }
                    pos++;
                    column++;
                }
                continue;
            }
            if (c == '$' && peek(1) == '{') {
                hasInterpolation = true;
                // consume up to matching }
                sb.append(c);
                pos++;
                column++;
                if (pos < length) {
                    sb.append(source.charAt(pos));
                    pos++;
                    column++;
                }
                int depth = 1;
                while (pos < length && depth > 0) {
                    char cc = source.charAt(pos);
                    sb.append(cc);
                    if (cc == '{') depth++;
                    else if (cc == '}') depth--;
                    if (cc == '\n') { line++; column = 1; } else { column++; }
                    pos++;
                }
                continue;
            }
            sb.append(c);
            pos++;
            column++;
        }
        if (!closed && pos >= length) {
            error(DiagnosticCode.LEXER_UNTERMINATED_STRING, "Unterminated string literal", startLine, startCol);
        }
        String raw = source.substring(start, pos);
        TokenType type = hasInterpolation ? TokenType.STRING_INTERP : TokenType.STRING;
        Token tok = new Token(type, raw, startLine, startCol, start);
        tokens.add(tok);
    }

    private void lexNumber() {
        int start = pos;
        int startCol = column;
        boolean isDouble = false;
        boolean isFloat = false;
        boolean isLong = false;
        // leading dot already handled by caller for fractional; here handle integer part
        while (pos < length && Character.isDigit(source.charAt(pos))) {
            pos++;
            column++;
        }
        // Check if integer part exceeds Integer.MAX_VALUE (2147483647) - auto-promote to long
        String intPart = source.substring(start, pos);
        if (!isDouble && !isFloat && !isLong) {
            try {
                Long.parseLong(intPart);
                if (Long.parseLong(intPart) > Integer.MAX_VALUE) {
                    isLong = true;
                }
            } catch (NumberFormatException ignored) {
                // Too large for long, keep as INTEGER (will error later)
            }
        }
        if (pos < length && source.charAt(pos) == '.' && peek(1) != '.' && pos + 1 < length && Character.isDigit(peek(1))) {
            isDouble = true;
            pos++;
            column++;
            while (pos < length && Character.isDigit(source.charAt(pos))) {
                pos++;
                column++;
            }
        }
        // exponent
        if (pos < length && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
            isDouble = true;
            pos++;
            column++;
            if (pos < length && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
                pos++;
                column++;
            }
            while (pos < length && Character.isDigit(source.charAt(pos))) {
                pos++;
                column++;
            }
        }
        // suffixes
        if (pos < length) {
            char s = source.charAt(pos);
            if (s == 'f' || s == 'F') {
                isFloat = true;
                isDouble = false;
                pos++;
                column++;
            } else if (s == 'd' || s == 'D') {
                isDouble = true;
                pos++;
                column++;
            } else if (s == 'l' || s == 'L') {
                isLong = true;
                pos++;
                column++;
            }
        }
        TokenType type;
        if (isFloat) type = TokenType.FLOAT_LITERAL;
        else if (isDouble) type = TokenType.DOUBLE_LITERAL;
        else if (isLong) type = TokenType.LONG_LITERAL;
        else type = TokenType.INTEGER;
        tokens.add(new Token(type, source.substring(start, pos), line, startCol, start));
    }

    private void lexIdentifierOrKeyword() {
        int start = pos;
        int startCol = column;
        while (pos < length && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_')) {
            pos++;
            column++;
        }
        String word = source.substring(start, pos);
        TokenType kw = KEYWORDS.get(word);
        TokenType type;
        if (kw != null) {
            type = kw;
        } else {
            type = TokenType.IDENTIFIER;
        }
        tokens.add(new Token(type, word, line, startCol, start));
    }

    private void lexOperatorOrPunctuation() {
        int start = pos;
        int startCol = column;
        char c = source.charAt(pos);
        char n = peek(1);
        switch (c) {
            case '+' -> {
                if (n == '+') { emit2(TokenType.INCREMENT, "++"); }
                else if (n == '=') { emit2(TokenType.PLUS_EQ, "+="); }
                else { emit(TokenType.PLUS, "+"); }
            }
            case '-' -> {
                if (n == '>') { emit2(TokenType.ARROW, "->"); }
                else if (n == '-') { emit2(TokenType.DECREMENT, "--"); }
                else if (n == '=') { emit2(TokenType.MINUS_EQ, "-="); }
                else { emit(TokenType.MINUS, "-"); }
            }
            case '*' -> {
                if (n == '=') { emit2(TokenType.STAR_EQ, "*="); }
                else { emit(TokenType.STAR, "*"); }
            }
            case '/' -> {
                if (n == '=') { emit2(TokenType.SLASH_EQ, "/="); }
                else { emit(TokenType.SLASH, "/"); }
            }
            case '%' -> {
                if (n == '=') { emit2(TokenType.PERCENT_EQ, "%="); }
                else { emit(TokenType.PERCENT, "%"); }
            }
            case '=' -> {
                if (n == '=') { emit2(TokenType.EQ_EQ, "=="); }
                else { emit(TokenType.EQ, "="); }
            }
            case '!' -> {
                if (n == '=') { emit2(TokenType.BANG_EQ, "!="); }
                else { emit(TokenType.BANG, "!"); }
            }
            case '<' -> {
                if (n == '=') { emit2(TokenType.LE, "<="); }
                else { emit(TokenType.LT, "<"); }
            }
            case '>' -> {
                if (n == '=') { emit2(TokenType.GE, ">="); }
                else { emit(TokenType.GT, ">"); }
            }
            case '&' -> {
                if (n == '&') { emit2(TokenType.AND_AND, "&&"); }
                else { error(DiagnosticCode.LEXER_UNEXPECTED_CHAR, "Unexpected '&'", line, startCol); pos++; column++; }
            }
            case '|' -> {
                if (n == '|') { emit2(TokenType.OR_OR, "||"); }
                else { error(DiagnosticCode.LEXER_UNEXPECTED_CHAR, "Unexpected '|'", line, startCol); pos++; column++; }
            }
            case '?' -> emit(TokenType.QUESTION, "?");
            case '.' -> {
                if (n == '.') { emit2(TokenType.RANGE, ".."); }
                else { emit(TokenType.DOT, "."); }
            }
            case ':' -> {
                if (n == ':') { emit2(TokenType.COLON_COLON, "::"); }
                else { emit(TokenType.COLON, ":"); }
            }
            case '(' -> emit(TokenType.LPAREN, "(");
            case ')' -> emit(TokenType.RPAREN, ")");
            case '{' -> emit(TokenType.LBRACE, "{");
            case '}' -> emit(TokenType.RBRACE, "}");
            case '[' -> emit(TokenType.LBRACKET, "[");
            case ']' -> emit(TokenType.RBRACKET, "]");
            case ',' -> emit(TokenType.COMMA, ",");
            case ';' -> emit(TokenType.SEMICOLON, ";");
            default -> {
                error(DiagnosticCode.LEXER_UNEXPECTED_CHAR, "Unexpected character: " + c, line, startCol);
                pos++;
                column++;
            }
        }
    }

    private void emit(TokenType type, String text) {
        tokens.add(new Token(type, text, line, column, pos));
        pos += text.length();
        column += text.length();
    }

    private void emit2(TokenType type, String text) {
        tokens.add(new Token(type, text, line, column, pos));
        pos += 2;
        column += 2;
    }

    private char peek(int ahead) {
        int i = pos + ahead;
        return i < length ? source.charAt(i) : '\0';
    }

    private void error(DiagnosticCode code, String message, int l, int col) {
        SourceRange range = SourceRange.of(filePath, l, col);
        diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, code, message, range));
    }
}
