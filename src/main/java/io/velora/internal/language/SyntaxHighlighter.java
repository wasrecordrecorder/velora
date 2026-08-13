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

    private static SyntaxToken.TokenType mapType(TokenType tt) {
        if (tt == null) return SyntaxToken.TokenType.ERROR;
        if (tt == TokenType.IDENTIFIER) return SyntaxToken.TokenType.IDENTIFIER;
        if (tt == TokenType.STRING || tt == TokenType.STRING_INTERP) return SyntaxToken.TokenType.STRING;
        if (tt == TokenType.INTEGER || tt == TokenType.LONG_LITERAL
                || tt == TokenType.FLOAT_LITERAL || tt == TokenType.DOUBLE_LITERAL) return SyntaxToken.TokenType.NUMBER;
        if (tt == TokenType.BOOLEAN || tt == TokenType.KW_TRUE || tt == TokenType.KW_FALSE) return SyntaxToken.TokenType.BOOLEAN;
        if (tt == TokenType.NULL || tt == TokenType.KW_NULL) return SyntaxToken.TokenType.BOOLEAN;
        if (tt == TokenType.ANNOTATION || tt == TokenType.AT) return SyntaxToken.TokenType.ANNOTATION;
        if (tt == TokenType.LBRACE || tt == TokenType.RBRACE || tt == TokenType.LPAREN || tt == TokenType.RPAREN
                || tt == TokenType.LBRACKET || tt == TokenType.RBRACKET || tt == TokenType.COMMA
                || tt == TokenType.DOT || tt == TokenType.COLON || tt == TokenType.SEMICOLON
                || tt == TokenType.ARROW || tt == TokenType.COLON_COLON) return SyntaxToken.TokenType.PUNCTUATION;
        if (tt == TokenType.PLUS || tt == TokenType.MINUS || tt == TokenType.STAR || tt == TokenType.SLASH
                || tt == TokenType.PERCENT || tt == TokenType.EQ || tt == TokenType.EQ_EQ
                || tt == TokenType.BANG || tt == TokenType.BANG_EQ || tt == TokenType.LT || tt == TokenType.LE
                || tt == TokenType.GT || tt == TokenType.GE || tt == TokenType.AND_AND || tt == TokenType.OR_OR
                || tt == TokenType.QUESTION || tt == TokenType.INCREMENT || tt == TokenType.DECREMENT) return SyntaxToken.TokenType.OPERATOR;
        if (tt.isKeyword()) return SyntaxToken.TokenType.KEYWORD;
        if (tt == TokenType.COMMENT) return SyntaxToken.TokenType.COMMENT;
        if (tt == TokenType.NEWLINE) return SyntaxToken.TokenType.NEWLINE;
        if (tt == TokenType.WHITESPACE) return SyntaxToken.TokenType.WHITESPACE;
        return SyntaxToken.TokenType.ERROR;
    }
}
