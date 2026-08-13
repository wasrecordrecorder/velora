package io.velora.api.compiler;

public record Diagnostic(
        DiagnosticSeverity severity,
        DiagnosticCode code,
        String message,
        SourceRange range
) {
    public Diagnostic {
        java.util.Objects.requireNonNull(severity);
        java.util.Objects.requireNonNull(code);
        java.util.Objects.requireNonNull(message);
    }

    public static Diagnostic error(DiagnosticCode code, String message, SourceRange range) {
        return new Diagnostic(DiagnosticSeverity.ERROR, code, message, range);
    }

    public static Diagnostic warning(DiagnosticCode code, String message, SourceRange range) {
        return new Diagnostic(DiagnosticSeverity.WARNING, code, message, range);
    }

    public static Diagnostic info(DiagnosticCode code, String message, SourceRange range) {
        return new Diagnostic(DiagnosticSeverity.INFO, code, message, range);
    }

    public boolean isError() {
        return severity == DiagnosticSeverity.ERROR;
    }

    public boolean isWarning() {
        return severity == DiagnosticSeverity.WARNING;
    }
}
