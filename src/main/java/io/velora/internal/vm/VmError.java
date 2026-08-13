package io.velora.internal.vm;

import io.velora.api.compiler.DiagnosticCode;

public record VmError(DiagnosticCode code, String message, int line, long fiberId) {
    public static VmError of(DiagnosticCode code, String message, int line, long fiberId) {
        return new VmError(code, message, line, fiberId);
    }
}
