package io.velora.internal.lexer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LexerV2Test {
    private List<Token> tokens(String source) {
        LexerResult result = new Lexer(source, "test.vls").lex();
        assertTrue(result.diagnostics().isEmpty(), "Lexer errors: " + result.diagnostics());
        return result.tokens();
    }

    private List<Token> significant(String source) {
        return tokens(source).stream().filter(token -> !token.isTrivia() && token.type() != TokenType.EOF).toList();
    }

    @Test
    void scriptKeyword() {
        assertEquals(TokenType.KW_SCRIPT, significant("script").get(0).type());
    }

    @Test
    void controlKeywords() {
        List<Token> tokens = significant("if else while for when return is in spawn async static");
        assertEquals(TokenType.KW_IF, tokens.get(0).type());
        assertEquals(TokenType.KW_IN, tokens.get(7).type());
        assertEquals(TokenType.KW_SPAWN, tokens.get(8).type());
    }

    @Test
    void annotationIsSingleToken() {
        Token token = significant("@Setting").get(0);
        assertEquals(TokenType.ANNOTATION, token.type());
        assertEquals("@Setting", token.text());
    }

    @Test
    @DisplayName("Dotted annotations are no longer a single token")
    void dottedAnnotationSplit() {
        List<Token> tokens = significant("@Event.Tick");
        assertEquals(3, tokens.size());
        assertEquals("@Event", tokens.get(0).text());
        assertEquals(TokenType.DOT, tokens.get(1).type());
        assertEquals("Tick", tokens.get(2).text());
    }

    @Test
    void lineComment() {
        List<Token> tokens = tokens("value = 1 // comment\nnext = 2");
        assertTrue(tokens.stream().anyMatch(token -> token.type() == TokenType.COMMENT && token.text().equals("// comment")));
    }

    @Test
    void blockComment() {
        List<Token> tokens = tokens("/* first\nsecond */ value = 1");
        assertTrue(tokens.stream().anyMatch(token -> token.type() == TokenType.COMMENT && token.text().contains("second")));
    }

    @Test
    void moduloRemainsPercent() {
        List<Token> tokens = significant("10 % 3");
        assertEquals(TokenType.PERCENT, tokens.get(1).type());
    }

    @Test
    void collectionGenericTokens() {
        List<Token> tokens = significant("list<Player>()");
        assertEquals("list", tokens.get(0).text());
        assertEquals(TokenType.LT, tokens.get(1).type());
        assertEquals("Player", tokens.get(2).text());
        assertEquals(TokenType.GT, tokens.get(3).type());
    }

    @Test
    void stringLiteral() {
        assertEquals(TokenType.STRING, significant("\"hello\"").get(0).type());
    }

    @Test
    void interpolationLiteral() {
        assertEquals(TokenType.STRING_INTERP, significant("\"hello ${name}\"").get(0).type());
    }

    @Test
    void numericLiterals() {
        List<Token> tokens = significant("1 2L 3.0f 4.0");
        assertEquals(TokenType.INTEGER, tokens.get(0).type());
        assertEquals(TokenType.LONG_LITERAL, tokens.get(1).type());
        assertEquals(TokenType.FLOAT_LITERAL, tokens.get(2).type());
        assertEquals(TokenType.DOUBLE_LITERAL, tokens.get(3).type());
    }

    @Test
    void booleanAndNullLiterals() {
        List<Token> tokens = significant("true false null");
        assertEquals(TokenType.KW_TRUE, tokens.get(0).type());
        assertEquals(TokenType.KW_FALSE, tokens.get(1).type());
        assertEquals(TokenType.KW_NULL, tokens.get(2).type());
    }

    @Test
    void comparisonOperators() {
        List<Token> tokens = significant("== != <= >= < >");
        assertEquals(TokenType.EQ_EQ, tokens.get(0).type());
        assertEquals(TokenType.BANG_EQ, tokens.get(1).type());
        assertEquals(TokenType.LE, tokens.get(2).type());
        assertEquals(TokenType.GE, tokens.get(3).type());
    }

    @Test
    void assignmentOperators() {
        List<Token> tokens = significant("= += -= *= /= %=");
        assertEquals(TokenType.EQ, tokens.get(0).type());
        assertEquals(TokenType.PLUS_EQ, tokens.get(1).type());
        assertEquals(TokenType.PERCENT_EQ, tokens.get(5).type());
    }

    @Test
    void nullableAndElvisTokens() {
        List<Token> tokens = significant("String? value = source?.name ?: \"x\"");
        assertTrue(tokens.stream().anyMatch(token -> token.type() == TokenType.QUESTION));
        assertTrue(tokens.stream().filter(token -> token.type() == TokenType.QUESTION).count() >= 2);
        assertTrue(tokens.stream().anyMatch(token -> token.type() == TokenType.COLON));
    }

    @Test
    void durationSyntax() {
        List<Token> tokens = significant("10.seconds");
        assertEquals(TokenType.INTEGER, tokens.get(0).type());
        assertEquals(TokenType.DOT, tokens.get(1).type());
        assertEquals("seconds", tokens.get(2).text());
    }

    @Test
    void sourceCoordinatesAdvanceAcrossComments() {
        List<Token> tokens = significant("// a\n/* b\nc */\nscript");
        assertEquals(4, tokens.get(0).line());
    }

    @Test
    void removedKeywordsArePlainIdentifiers() {
        List<Token> tokens = significant("settings entry event void package import private public");
        assertTrue(tokens.stream().allMatch(token -> token.type() == TokenType.IDENTIFIER));
    }
}
