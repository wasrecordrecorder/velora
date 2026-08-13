package io.velora.api.compiler;

import java.util.List;

public record CompileResult(
        boolean success,
        String scriptId,
        List<Diagnostic> diagnostics,
        byte[] bytecode,
        String registryHash,
        String sourceHash
) {
    public CompileResult {
        java.util.Objects.requireNonNull(scriptId);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static CompileResult success(String scriptId, byte[] bytecode, String registryHash, String sourceHash) {
        return new CompileResult(true, scriptId, List.of(), bytecode, registryHash, sourceHash);
    }

    public static CompileResult failure(String scriptId, List<Diagnostic> diagnostics) {
        return new CompileResult(false, scriptId, diagnostics, null, null, null);
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    public List<Diagnostic> warnings() {
        return diagnostics.stream().filter(Diagnostic::isWarning).toList();
    }

    public int errorCount() {
        return (int) diagnostics.stream().filter(Diagnostic::isError).count();
    }

    public int warningCount() {
        return (int) diagnostics.stream().filter(Diagnostic::isWarning).count();
    }
}
