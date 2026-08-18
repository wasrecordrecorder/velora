package io.velora.api.debug;

public record RuntimeError(
        String scriptId,
        long fiberId,
        String functionName,
        String errorType,
        String message,
        int line,
        String stackTrace,
        long timestampNanos
) {
    public RuntimeError {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(errorType);
        java.util.Objects.requireNonNull(message);
    }

    public RuntimeError(String scriptId, long fiberId, String functionName, String errorType, String message, String stackTrace, long timestampNanos) {
        this(scriptId, fiberId, functionName, errorType, message, 0, stackTrace, timestampNanos);
    }

    public static RuntimeError of(String scriptId, long fiberId, String errorType, String message, String stackTrace) {
        return new RuntimeError(scriptId, fiberId, null, errorType, message, 0, stackTrace, System.nanoTime());
    }
}
