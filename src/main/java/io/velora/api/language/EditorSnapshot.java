package io.velora.api.language;

import io.velora.api.compiler.Diagnostic;

import java.util.List;
import java.util.Optional;

public record EditorSnapshot(
        String scriptId,
        String filePath,
        String content,
        String contentHash,
        List<Diagnostic> diagnostics,
        List<SyntaxToken> tokens,
        long revisionToken
) {
    public EditorSnapshot {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(filePath);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
    }

    public static EditorSnapshot of(String scriptId, String filePath, String content, String contentHash, long revisionToken) {
        return new EditorSnapshot(scriptId, filePath, content, contentHash, List.of(), List.of(), revisionToken);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(Diagnostic::isError);
    }

    public int errorCount() {
        return (int) diagnostics.stream().filter(Diagnostic::isError).count();
    }
}
