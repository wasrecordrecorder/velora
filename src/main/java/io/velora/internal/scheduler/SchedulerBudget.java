package io.velora.internal.scheduler;

import io.velora.api.VeloraLimits;

public final class SchedulerBudget {
    private final VeloraLimits limits;
    private int engineInstructionsThisTick;
    private int scriptInstructionsThisTick;
    private long engineWallTimeStartNanos;

    public SchedulerBudget(VeloraLimits limits) {
        this.limits = limits;
    }

    public void resetTick(long nowNanos) {
        engineInstructionsThisTick = 0;
        scriptInstructionsThisTick = 0;
        engineWallTimeStartNanos = System.nanoTime();
    }

    public boolean canExecuteFiber(ScriptFiber fiber) {
        return fiber.instructionsThisTick() < limits.instructionsPerFiberTick()
                && scriptInstructionsThisTick < limits.instructionsPerScriptTick()
                && engineInstructionsThisTick < limits.instructionsPerEngineTick();
    }

    public void recordInstructions(int count) {
        engineInstructionsThisTick += count;
        scriptInstructionsThisTick += count;
    }

    public boolean wallTimeExceeded(long nowNanos) {
        return (nowNanos - engineWallTimeStartNanos) > limits.engineWallTimeNanosPerTick();
    }

    public int remainingFiberInstructions(ScriptFiber fiber) {
        return limits.instructionsPerFiberTick() - fiber.instructionsThisTick();
    }

    public VeloraLimits limits() { return limits; }
}
