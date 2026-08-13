package io.velora.api.debug;

public record RuntimeError(
        String scriptId,
        long fiberId,
        String functionName,
        String errorType,
        String message,
        String stackTrace,
        long timestampNanos
) {
    public RuntimeError {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(errorType);
        java.util.Objects.requireNonNull(message);
    }

    public static RuntimeError of(String scriptId, long fiberId, String errorType, String message, String stackTrace) {
        return new RuntimeError(scriptId, fiberId, null, errorType, message, stackTrace, System.nanoTime());
    }
}
