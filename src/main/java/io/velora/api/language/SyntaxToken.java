package io.velora.api.language;

public record SyntaxToken(
        TokenType type,
        String text,
        int line,
        int column,
        int length
) {
    public SyntaxToken {
        java.util.Objects.requireNonNull(type);
        java.util.Objects.requireNonNull(text);
        if (line < 1) line = 1;
        if (column < 1) column = 1;
        if (length < 0) length = text.length();
    }

    public enum TokenType {
        KEYWORD, IDENTIFIER, ANNOTATION, STRING, NUMBER, BOOLEAN, OPERATOR,
        PUNCTUATION, COMMENT, WHITESPACE, NEWLINE, ERROR, EOF
    }

    public static SyntaxToken of(TokenType type, String text, int line, int column) {
        return new SyntaxToken(type, text, line, column, text.length());
    }
}
