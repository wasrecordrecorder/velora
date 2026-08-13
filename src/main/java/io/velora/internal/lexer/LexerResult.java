package io.velora.internal.lexer;

import io.velora.api.compiler.Diagnostic;

import java.util.List;

public record LexerResult(
        List<Token> tokens,
        List<Diagnostic> diagnostics
) {
    public LexerResult {
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }

    public int errorCount() {
        return diagnostics.size();
    }
}
