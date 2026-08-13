package io.velora.internal.lexer;

/**
 * A lexical token with source position.
 */
public final class Token {

    private final TokenType type;
    private final String text;
    private final int line;
    private final int column;
    private final int offset;

    public Token(TokenType type, String text, int line, int column, int offset) {
        this.type = type;
        this.text = text;
        this.line = line;
        this.column = column;
        this.offset = offset;
    }

    public static Token eof(int line, int column, int offset) {
        return new Token(TokenType.EOF, "", line, column, offset);
    }

    public TokenType type() {
        return type;
    }

    public String text() {
        return text;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public int offset() {
        return offset;
    }

    public int length() {
        return text.length();
    }

    public boolean is(TokenType t) {
        return type == t;
    }

    public boolean isAny(TokenType... types) {
        for (TokenType t : types) {
            if (type == t) return true;
        }
        return false;
    }

    public boolean isEof() {
        return type == TokenType.EOF;
    }

    public boolean isKeyword(String kw) {
        return type.isKeyword() && text.equals(kw);
    }

    public boolean isTrivia() {
        return type.isTrivia();
    }

    @Override
    public String toString() {
        return type + "('" + text + "'@" + line + ":" + column + ")";
    }
}
