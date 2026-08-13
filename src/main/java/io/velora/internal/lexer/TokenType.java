package io.velora.internal.lexer;

public enum TokenType {
    // Literals
    IDENTIFIER,
    INTEGER,
    LONG_LITERAL,
    FLOAT_LITERAL,
    DOUBLE_LITERAL,
    STRING,
    STRING_INTERP,
    BOOLEAN,
    NULL,

    // Annotations
    ANNOTATION,      // @Name or @Name.Sub

    // Keywords
    KW_SCRIPT,
    KW_SETTINGS,
    KW_STATIC,
    KW_ASYNC,
    KW_ENTRY,
    KW_EVENT,
    KW_VOID,
    KW_IF,
    KW_ELSE,
    KW_WHILE,
    KW_FOR,
    KW_WHEN,
    KW_RETURN,
    KW_IMPORT,
    KW_PACKAGE,
    KW_PRIVATE,
    KW_PUBLIC,
    KW_IS,
    KW_IN,
    KW_SPAWN,
    KW_TRUE,
    KW_FALSE,
    KW_NULL,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, EQ_EQ, BANG_EQ, LT, LE, GT, GE,
    AND_AND, OR_OR, BANG, QUESTION, DOT,
    RANGE,             // .. range operator
    COLON, COLON_COLON, ARROW, INCREMENT, DECREMENT,
    PLUS_EQ, MINUS_EQ, STAR_EQ, SLASH_EQ, PERCENT_EQ,
    HASH,              // # for const declarations

    // Punctuation
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    COMMA, SEMICOLON, AT,

    // Structural
    NEWLINE,
    COMMENT,
    WHITESPACE,
    ERROR,
    EOF;

    public boolean isKeyword() {
        return name().startsWith("KW_");
    }

    public boolean isTrivia() {
        return this == WHITESPACE || this == COMMENT || this == NEWLINE;
    }
}
