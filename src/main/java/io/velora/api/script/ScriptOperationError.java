package io.velora.api.script;

import io.velora.api.compiler.Diagnostic;

public record ScriptOperationError(
        String scriptId,
        String code,
        String message,
        Diagnostic diagnostic
) {
    public ScriptOperationError {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(code);
        java.util.Objects.requireNonNull(message);
    }
}
