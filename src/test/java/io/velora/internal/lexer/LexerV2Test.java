package io.velora.internal.lexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class LexerV2Test {

    private List<Token> sig(String source) {
        LexerResult r = new Lexer(source, "test.vls").lex();
        return r.tokens().stream().filter(t -> !t.isTrivia()).collect(Collectors.toList());
    }

    private LexerResult lex(String source) {
        return new Lexer(source, "test.vls").lex();
    }

    // === V2 Keywords ===

    @Test
    @DisplayName("All V2 keywords are tokenized")
    void allV2Keywords() {
        String src = "script settings async entry event static if else while for when return true false null void";
        List<Token> tokens = sig(src);
        assertEquals(TokenType.KW_SCRIPT, tokens.get(0).type());
        assertEquals(TokenType.KW_SETTINGS, tokens.get(1).type());
        assertEquals(TokenType.KW_ASYNC, tokens.get(2).type());
        assertEquals(TokenType.KW_ENTRY, tokens.get(3).type());
        assertEquals(TokenType.KW_EVENT, tokens.get(4).type());
        assertEquals(TokenType.KW_STATIC, tokens.get(5).type());
        assertEquals(TokenType.KW_IF, tokens.get(6).type());
        assertEquals(TokenType.KW_ELSE, tokens.get(7).type());
        assertEquals(TokenType.KW_WHILE, tokens.get(8).type());
        assertEquals(TokenType.KW_FOR, tokens.get(9).type());
        assertEquals(TokenType.KW_WHEN, tokens.get(10).type());
        assertEquals(TokenType.KW_RETURN, tokens.get(11).type());
        assertEquals(TokenType.KW_TRUE, tokens.get(12).type());
        assertEquals(TokenType.KW_FALSE, tokens.get(13).type());
        assertEquals(TokenType.KW_NULL, tokens.get(14).type());
        assertEquals(TokenType.KW_VOID, tokens.get(15).type());
    }

    @Test
    @DisplayName("V1 keywords val/var/fun/suspend are NOT keywords in V2")
    void v1KeywordsAreIdentifiers() {
        List<Token> tokens = sig("val var fun suspend");
        assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
        assertEquals("val", tokens.get(0).text());
        assertEquals(TokenType.IDENTIFIER, tokens.get(1).type());
        assertEquals("var", tokens.get(1).text());
        assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
        assertEquals("fun", tokens.get(2).text());
        assertEquals(TokenType.IDENTIFIER, tokens.get(3).type());
        assertEquals("suspend", tokens.get(3).text());
    }

    // === V2 Tokens ===

    @Test
    @DisplayName("HASH token for const declarations")
    void hashToken() {
        List<Token> tokens = sig("#int MAX = 5");
        assertEquals(TokenType.HASH, tokens.get(0).type());
        assertEquals("#", tokens.get(0).text());
    }

    @Test
    @DisplayName("RANGE token (..)")
    void rangeToken() {
        List<Token> tokens = sig("8..128");
        assertEquals(TokenType.INTEGER, tokens.get(0).type());
        assertEquals(TokenType.RANGE, tokens.get(1).type());
        assertEquals("..", tokens.get(1).text());
        assertEquals(TokenType.INTEGER, tokens.get(2).type());
    }

    @Test
    @DisplayName("Compound assignment operators")
    void compoundOperators() {
        List<Token> tokens = sig("x += 1 y -= 2 z *= 3 w /= 4 v %= 5");
        assertEquals(TokenType.PLUS_EQ, tokens.get(1).type());
        assertEquals(TokenType.MINUS_EQ, tokens.get(4).type());
        assertEquals(TokenType.STAR_EQ, tokens.get(7).type());
        assertEquals(TokenType.SLASH_EQ, tokens.get(10).type());
        assertEquals(TokenType.PERCENT_EQ, tokens.get(13).type());
    }

    @Test
    @DisplayName("Increment and decrement operators")
    void incrementDecrement() {
        List<Token> tokens = sig("x++ y--");
        assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
        assertEquals(TokenType.INCREMENT, tokens.get(1).type());
        assertEquals("++", tokens.get(1).text());
        assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
        assertEquals(TokenType.DECREMENT, tokens.get(3).type());
        assertEquals("--", tokens.get(3).text());
    }

    @Test
    @DisplayName("AND_AND and OR_OR operators")
    void booleanOperators() {
        List<Token> tokens = sig("a && b || c");
        assertEquals(TokenType.AND_AND, tokens.get(1).type());
        assertEquals("&&", tokens.get(1).text());
        assertEquals(TokenType.OR_OR, tokens.get(3).type());
        assertEquals("||", tokens.get(3).text());
    }

    @Test
    @DisplayName("String interpolation token")
    void stringInterpolation() {
        LexerResult r = lex("String s = \"v${value}\"");
        List<Token> tokens = r.tokens().stream().filter(t -> !t.isTrivia()).collect(Collectors.toList());
        boolean hasInterp = tokens.stream().anyMatch(t -> t.type() == TokenType.STRING_INTERP);
        assertTrue(hasInterp, "Should have STRING_INTERP token for ${...} in string");
    }

    // === Numeric Literals ===

    @Test
    @DisplayName("Integer literal")
    void integerLiteral() {
        List<Token> tokens = sig("42");
        assertEquals(TokenType.INTEGER, tokens.get(0).type());
        assertEquals("42", tokens.get(0).text());
    }

    @Test
    @DisplayName("Long literal with L suffix")
    void longLiteral() {
        List<Token> tokens = sig("100L");
        assertEquals(TokenType.LONG_LITERAL, tokens.get(0).type());
        assertEquals("100L", tokens.get(0).text());
    }

    @Test
    @DisplayName("Float literal with f suffix")
    void floatLiteral() {
        List<Token> tokens = sig("3.14f");
        assertEquals(TokenType.FLOAT_LITERAL, tokens.get(0).type());
    }

    @Test
    @DisplayName("Double literal with d suffix")
    void doubleLiteral() {
        List<Token> tokens = sig("2.718d");
        assertEquals(TokenType.DOUBLE_LITERAL, tokens.get(0).type());
    }

    @Test
    @DisplayName("Negative integer")
    void negativeInteger() {
        List<Token> tokens = sig("-42");
        assertEquals(TokenType.MINUS, tokens.get(0).type());
        assertEquals(TokenType.INTEGER, tokens.get(1).type());
        assertEquals("42", tokens.get(1).text());
    }

    @Test
    @DisplayName("Zero")
    void zeroLiteral() {
        List<Token> tokens = sig("0");
        assertEquals(TokenType.INTEGER, tokens.get(0).type());
        assertEquals("0", tokens.get(0).text());
    }

    @Test
    @DisplayName("Large integer")
    void largeInteger() {
        List<Token> tokens = sig("2147483647");
        assertEquals(TokenType.INTEGER, tokens.get(0).type());
    }

    // === String Literals ===

    @Test
    @DisplayName("Simple string literal")
    void simpleString() {
        List<Token> tokens = sig("\"hello\"");
        assertEquals(TokenType.STRING, tokens.get(0).type());
    }

    @Test
    @DisplayName("String with escape sequences")
    void escapedString() {
        List<Token> tokens = sig("\"hello\\nworld\\t!\"");
        assertEquals(TokenType.STRING, tokens.get(0).type());
    }

    @Test
    @DisplayName("Empty string literal")
    void emptyString() {
        List<Token> tokens = sig("\"\"");
        assertEquals(TokenType.STRING, tokens.get(0).type());
    }

    @Test
    @DisplayName("String with Unicode")
    void unicodeString() {
        List<Token> tokens = sig("\"Ω\"");
        assertEquals(TokenType.STRING, tokens.get(0).type());
    }

    @Test
    @DisplayName("Unterminated string produces error")
    void unterminatedString() {
        LexerResult r = lex("\"unterminated");
        assertFalse(r.diagnostics().isEmpty(), "Unterminated string should produce a diagnostic");
    }

    // === Annotations ===

    @Test
    @DisplayName("Simple annotation @Name")
    void simpleAnnotation() {
        List<Token> tokens = sig("@Script");
        assertEquals(TokenType.ANNOTATION, tokens.get(0).type());
        assertEquals("@Script", tokens.get(0).text());
    }

    @Test
    @DisplayName("Nested annotation @Name.Sub")
    void nestedAnnotation() {
        List<Token> tokens = sig("@Number.Slider");
        assertEquals(TokenType.ANNOTATION, tokens.get(0).type());
        assertEquals("@Number.Slider", tokens.get(0).text());
    }

    @Test
    @DisplayName("Event annotation @Event.ChatMessage")
    void eventAnnotation() {
        List<Token> tokens = sig("@Event.ChatMessage");
        assertEquals(TokenType.ANNOTATION, tokens.get(0).type());
    }

    // === Operators ===

    @Test
    @DisplayName("Arithmetic operators")
    void arithmeticOperators() {
        List<Token> tokens = sig("a + b - c * d / e % f");
        assertEquals(TokenType.PLUS, tokens.get(1).type());
        assertEquals(TokenType.MINUS, tokens.get(3).type());
        assertEquals(TokenType.STAR, tokens.get(5).type());
        assertEquals(TokenType.SLASH, tokens.get(7).type());
        assertEquals(TokenType.PERCENT, tokens.get(9).type());
    }

    @Test
    @DisplayName("Comparison operators")
    void comparisonOperators() {
        List<Token> tokens = sig("a == b != c < d <= e > f >= g");
        assertEquals(TokenType.EQ_EQ, tokens.get(1).type());
        assertEquals(TokenType.BANG_EQ, tokens.get(3).type());
        assertEquals(TokenType.LT, tokens.get(5).type());
        assertEquals(TokenType.LE, tokens.get(7).type());
        assertEquals(TokenType.GT, tokens.get(9).type());
        assertEquals(TokenType.GE, tokens.get(11).type());
    }

    @Test
    @DisplayName("Assignment operator")
    void assignmentOperator() {
        List<Token> tokens = sig("x = 5");
        assertEquals(TokenType.EQ, tokens.get(1).type());
        assertEquals("=", tokens.get(1).text());
    }

    @Test
    @DisplayName("Bang (not) operator")
    void bangOperator() {
        List<Token> tokens = sig("!true");
        assertEquals(TokenType.BANG, tokens.get(0).type());
    }

    @Test
    @DisplayName("Dot operator for member access")
    void dotOperator() {
        List<Token> tokens = sig("obj.field");
        assertEquals(TokenType.DOT, tokens.get(1).type());
    }

    // === Punctuation ===

    @Test
    @DisplayName("Braces, parens, brackets")
    void punctuation() {
        List<Token> tokens = sig("{ } ( ) [ ]");
        assertEquals(TokenType.LBRACE, tokens.get(0).type());
        assertEquals(TokenType.RBRACE, tokens.get(1).type());
        assertEquals(TokenType.LPAREN, tokens.get(2).type());
        assertEquals(TokenType.RPAREN, tokens.get(3).type());
        assertEquals(TokenType.LBRACKET, tokens.get(4).type());
        assertEquals(TokenType.RBRACKET, tokens.get(5).type());
    }

    @Test
    @DisplayName("Comma and semicolon")
    void commaSemicolon() {
        List<Token> tokens = sig("a, b; c");
        assertEquals(TokenType.COMMA, tokens.get(1).type());
        assertEquals(TokenType.SEMICOLON, tokens.get(3).type());
    }

    // === EOF ===

    @Test
    @DisplayName("EOF token is always present")
    void eofToken() {
        List<Token> tokens = sig("script Test {}");
        assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type());
    }

    @Test
    @DisplayName("Empty source produces EOF")
    void emptySourceEof() {
        List<Token> tokens = sig("");
        assertEquals(TokenType.EOF, tokens.get(0).type());
    }

    // === Full Script Lexing ===

    @Test
    @DisplayName("Complete V2 script lexes without errors")
    void completeV2Script() {
        String src = """
            @Script(name = "Test", version = "1.0.0")
            @Permissions(Permission.WORLD_READ)
            script Test {
                settings {
                    @Number radius ("R", 8..128, 1, 32, @Number.Slider)
                    @Boolean enabled ("E", true)
                }
                int ticks = 0
                static int total = 0
                #int MAX = 5
                int answer() { return 42 }
                async void run() { delay(500) }
                entry onLoad() { }
                async event onTick(TickEvent e) { return false }
            }
            """;
        LexerResult r = lex(src);
        assertTrue(r.diagnostics().isEmpty(), "Lexer errors: " + r.diagnostics());
    }

    @Test
    @DisplayName("Minimal script lexes without errors")
    void minimalScript() {
        LexerResult r = lex("script Test {}");
        assertFalse(r.hasErrors(), "Minimal script should have no errors");
    }

    // === Token positions ===

    @Test
    @DisplayName("Token line and column are correct")
    void tokenPositions() {
        LexerResult r = lex("script Test {}");
        List<Token> tokens = r.tokens().stream().filter(t -> !t.isTrivia()).collect(Collectors.toList());
        Token scriptToken = tokens.get(0);
        assertEquals(1, scriptToken.line());
        assertEquals(0, scriptToken.column());
    }

    @Test
    @DisplayName("Multi-line source has correct line numbers")
    void multiLinePositions() {
        String src = "script Test {\n    int x = 5\n}";
        LexerResult r = lex(src);
        List<Token> tokens = r.tokens().stream().filter(t -> !t.isTrivia()).collect(Collectors.toList());
        Token intToken = tokens.stream().filter(t -> t.type() == TokenType.IDENTIFIER && t.text().equals("int")).findFirst().orElse(null);
        assertNotNull(intToken);
        assertEquals(2, intToken.line());
    }
}
