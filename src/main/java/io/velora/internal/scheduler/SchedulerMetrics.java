package io.velora.internal.scheduler;

public final class SchedulerMetrics {
    private long totalTicks;
    private long totalFibersExecuted;
    private long totalInstructions;
    private long totalWallTimeNanos;
    private int throttledScripts;
    private int lastTickInstructions;

    public void recordTick(int fibersExecuted, int instructions, long wallTimeNanos) {
        totalTicks++;
        totalFibersExecuted += fibersExecuted;
        totalInstructions += instructions;
        totalWallTimeNanos += wallTimeNanos;
        lastTickInstructions = instructions;
    }

    public void recordThrottle() { throttledScripts++; }

    public long totalTicks() { return totalTicks; }
    public long totalFibersExecuted() { return totalFibersExecuted; }
    public long totalInstructions() { return totalInstructions; }
    public long totalWallTimeNanos() { return totalWallTimeNanos; }
    public int throttledScripts() { return throttledScripts; }
    public int lastTickInstructions() { return lastTickInstructions; }

    public void reset() {
        totalTicks = 0;
        totalFibersExecuted = 0;
        totalInstructions = 0;
        totalWallTimeNanos = 0;
        throttledScripts = 0;
    }
}
