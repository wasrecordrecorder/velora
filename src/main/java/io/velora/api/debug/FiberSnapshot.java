package io.velora.api.debug;

public record FiberSnapshot(
        long fiberId,
        long parentFiberId,
        String scriptId,
        String functionName,
        String state,
        int instructionPointer,
        long instructionsExecuted,
        long createdAtNanos
) {
    public FiberSnapshot {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(state);
    }
}
