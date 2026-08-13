package io.velora.api.debug;

public record TaskSnapshot(
    long taskId,
    String scriptId,
    String state,
    String description,
    long createdAtNanos
) {
    public TaskSnapshot {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(state);
    }
}
