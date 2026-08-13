package io.velora.internal.parser;

import io.velora.api.compiler.Diagnostic;
import io.velora.internal.ast.ScriptNode;

import java.util.List;

public record ParseResult(
        ScriptNode scriptNode,
        List<Diagnostic> diagnostics
) {
    public ParseResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(Diagnostic::isError);
    }

    public int errorCount() {
        return (int) diagnostics.stream().filter(Diagnostic::isError).count();
    }

    public static ParseResult success(ScriptNode scriptNode) {
        return new ParseResult(scriptNode, List.of());
    }

    public static ParseResult failure(List<Diagnostic> diagnostics) {
        return new ParseResult(null, diagnostics);
    }
}
