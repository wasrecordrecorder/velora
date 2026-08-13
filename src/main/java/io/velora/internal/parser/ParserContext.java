package io.velora.internal.parser;

import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.compiler.DiagnosticSeverity;
import io.velora.api.compiler.SourceRange;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenStream;
import io.velora.internal.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

public final class ParserContext {

    private final TokenStream tokens;
    private final String filePath;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public ParserContext(TokenStream tokens, String filePath) {
        this.tokens = tokens;
        this.filePath = filePath;
    }

    public TokenStream tokens() { return tokens; }
    public String filePath() { return filePath; }
    public List<Diagnostic> diagnostics() { return diagnostics; }

    public Token peek() { return tokens.peek(); }
    public Token advance() { return tokens.advance(); }

    public boolean check(TokenType type) {
        skipTrivia();
        return tokens.check(type);
    }

    public boolean match(TokenType type) {
        skipTrivia();
        return tokens.match(type);
    }

    public Token expect(TokenType type, String message) {
        skipTrivia();
        if (!tokens.check(type)) {
            Token current = tokens.peek();
            error(DiagnosticCode.PARSER_MISSING_TOKEN, message + " (found " + current.type() + " '" + current.text() + "' at line " + current.line() + ")", current);
            return current;
        }
        return tokens.advance();
    }

    public Token expect(TokenType type) {
        return expect(type, "Expected " + type);
    }

    public void skipTrivia() {
        while (tokens.check(TokenType.WHITESPACE) || tokens.check(TokenType.NEWLINE) || tokens.check(TokenType.COMMENT)) {
            tokens.advance();
        }
    }

    public void error(DiagnosticCode code, String message, Token token) {
        SourceRange range = SourceRange.of(filePath, token.line(), token.column());
        diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, code, message, range));
    }

    public void error(DiagnosticCode code, String message, int line, int column) {
        SourceRange range = SourceRange.of(filePath, line, column);
        diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, code, message, range));
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(Diagnostic::isError);
    }

    public int currentLine() {
        return peek().line();
    }

    public int currentColumn() {
        return peek().column();
    }
}
