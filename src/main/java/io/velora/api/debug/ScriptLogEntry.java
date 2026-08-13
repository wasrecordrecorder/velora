package io.velora.api.debug;

public record ScriptLogEntry(
        String scriptId,
        long fiberId,
        Level level,
        String message,
        long timestampNanos
) {
    public ScriptLogEntry {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(level);
        java.util.Objects.requireNonNull(message);
    }

    public enum Level { DEBUG, INFO, WARN, ERROR }

    public static ScriptLogEntry info(String scriptId, long fiberId, String message) {
        return new ScriptLogEntry(scriptId, fiberId, Level.INFO, message, System.nanoTime());
    }

    public static ScriptLogEntry error(String scriptId, long fiberId, String message) {
        return new ScriptLogEntry(scriptId, fiberId, Level.ERROR, message, System.nanoTime());
    }
}
