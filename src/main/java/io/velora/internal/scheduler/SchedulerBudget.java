package io.velora.internal.scheduler;

import io.velora.api.VeloraLimits;

import java.util.HashMap;
import java.util.Map;

public final class SchedulerBudget {
    private final VeloraLimits limits;
    private final Map<String, Integer> scriptInstructions = new HashMap<>();
    private int engineInstructions;
    private long wallTimeStartNanos;

    public SchedulerBudget(VeloraLimits limits) {
        this.limits = limits;
    }

    public void resetTick(long nowNanos) {
        scriptInstructions.clear();
        engineInstructions = 0;
        wallTimeStartNanos = System.nanoTime();
    }

    public boolean canExecuteFiber(ScriptFiber fiber) {
        return fiber.instructionsThisTick() < limits.instructionsPerFiberTick()
                && scriptInstructions.getOrDefault(fiber.scriptId(), 0) < limits.instructionsPerScriptTick()
                && engineInstructions < limits.instructionsPerEngineTick();
    }

    public boolean engineInstructionsExceeded() {
        return engineInstructions >= limits.instructionsPerEngineTick();
    }

    public boolean scriptInstructionsExceeded(String scriptId) {
        return scriptInstructions.getOrDefault(scriptId, 0) >= limits.instructionsPerScriptTick();
    }

    public void recordInstructions(String scriptId, int count) {
        engineInstructions += count;
        scriptInstructions.merge(scriptId, count, Integer::sum);
    }

    public boolean wallTimeExceeded(long nowNanos) {
        return nowNanos - wallTimeStartNanos > limits.engineWallTimeNanosPerTick();
    }

    public int remainingFiberInstructions(ScriptFiber fiber) {
        return Math.max(0, limits.instructionsPerFiberTick() - fiber.instructionsThisTick());
    }

    public VeloraLimits limits() { return limits; }
}
