package io.velora.api.debug;

public record ProfilerSnapshot(
        String scriptId,
        long tickTimeNanos,
        long instructionsExecuted,
        long apiCostConsumed,
        long memoryUsedBytes,
        int activeFibers,
        int activeTasks,
        int eventQueueDepth,
        int droppedEvents,
        long apiCalls,
        long failures,
        long cancellations,
        long coalescedEvents,
        int maxQueueDepth
) {
    public ProfilerSnapshot {
        java.util.Objects.requireNonNull(scriptId);
    }

    public static ProfilerSnapshot empty(String scriptId) {
        return new ProfilerSnapshot(scriptId, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
