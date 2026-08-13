package io.velora.api;

/**
 * Resource limits for the Velora engine.
 */
public final class VeloraLimits {

    private final int instructionsPerFiberTick;
    private final int instructionsPerScriptTick;
    private final int instructionsPerEngineTick;
    private final int apiCostPerScriptTick;
    private final long memoryPerScript;
    private final int maxFibersPerScript;
    private final int maxTasksPerScript;
    private final int maxEventQueuePerScript;
    private final int maxCallDepth;
    private final int maxStringLength;
    private final int maxCollectionElements;
    private final int maxCollectionDepth;
    private final long engineWallTimeNanosPerTick;

    private VeloraLimits(Builder b) {
        this.instructionsPerFiberTick = b.instructionsPerFiberTick;
        this.instructionsPerScriptTick = b.instructionsPerScriptTick;
        this.instructionsPerEngineTick = b.instructionsPerEngineTick;
        this.apiCostPerScriptTick = b.apiCostPerScriptTick;
        this.memoryPerScript = b.memoryPerScript;
        this.maxFibersPerScript = b.maxFibersPerScript;
        this.maxTasksPerScript = b.maxTasksPerScript;
        this.maxEventQueuePerScript = b.maxEventQueuePerScript;
        this.maxCallDepth = b.maxCallDepth;
        this.maxStringLength = b.maxStringLength;
        this.maxCollectionElements = b.maxCollectionElements;
        this.maxCollectionDepth = b.maxCollectionDepth;
        this.engineWallTimeNanosPerTick = b.engineWallTimeNanosPerTick;
    }

    public static VeloraLimits defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int instructionsPerFiberTick() { return instructionsPerFiberTick; }
    public int instructionsPerScriptTick() { return instructionsPerScriptTick; }
    public int instructionsPerEngineTick() { return instructionsPerEngineTick; }
    public int apiCostPerScriptTick() { return apiCostPerScriptTick; }
    public long memoryPerScript() { return memoryPerScript; }
    public int maxFibersPerScript() { return maxFibersPerScript; }
    public int maxTasksPerScript() { return maxTasksPerScript; }
    public int maxEventQueuePerScript() { return maxEventQueuePerScript; }
    public int maxCallDepth() { return maxCallDepth; }
    public int maxStringLength() { return maxStringLength; }
    public int maxCollectionElements() { return maxCollectionElements; }
    public int maxCollectionDepth() { return maxCollectionDepth; }
    public long engineWallTimeNanosPerTick() { return engineWallTimeNanosPerTick; }

    public static final class Builder {
        private int instructionsPerFiberTick = 5_000;
        private int instructionsPerScriptTick = 30_000;
        private int instructionsPerEngineTick = 100_000;
        private int apiCostPerScriptTick = 5_000;
        private long memoryPerScript = 32L * 1024L * 1024L;
        private int maxFibersPerScript = 64;
        private int maxTasksPerScript = 64;
        private int maxEventQueuePerScript = 256;
        private int maxCallDepth = 128;
        private int maxStringLength = 1_000_000;
        private int maxCollectionElements = 100_000;
        private int maxCollectionDepth = 32;
        private long engineWallTimeNanosPerTick = 10_000_000L; // 10 ms

        public Builder instructionsPerFiberTick(int v) { this.instructionsPerFiberTick = v; return this; }
        public Builder instructionsPerScriptTick(int v) { this.instructionsPerScriptTick = v; return this; }
        public Builder instructionsPerEngineTick(int v) { this.instructionsPerEngineTick = v; return this; }
        public Builder apiCostPerScriptTick(int v) { this.apiCostPerScriptTick = v; return this; }
        public Builder memoryPerScript(long v) { this.memoryPerScript = v; return this; }
        public Builder maxFibersPerScript(int v) { this.maxFibersPerScript = v; return this; }
        public Builder maxTasksPerScript(int v) { this.maxTasksPerScript = v; return this; }
        public Builder maxEventQueuePerScript(int v) { this.maxEventQueuePerScript = v; return this; }
        public Builder maxCallDepth(int v) { this.maxCallDepth = v; return this; }
        public Builder maxStringLength(int v) { this.maxStringLength = v; return this; }
        public Builder maxCollectionElements(int v) { this.maxCollectionElements = v; return this; }
        public Builder maxCollectionDepth(int v) { this.maxCollectionDepth = v; return this; }
        public Builder engineWallTimeNanosPerTick(long v) { this.engineWallTimeNanosPerTick = v; return this; }

        public VeloraLimits build() {
            return new VeloraLimits(this);
        }
    }
}
