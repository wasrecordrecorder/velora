package io.velora.internal.security;

public final class ApiCostController {
    private int costThisTick;
    private final int maxCostPerTick;

    public ApiCostController(int maxCostPerTick) {
        this.maxCostPerTick = maxCostPerTick;
    }

    public boolean canCall(int cost) {
        return costThisTick + cost <= maxCostPerTick;
    }

    public void recordCall(int cost) {
        costThisTick += cost;
    }

    public void resetTick() { costThisTick = 0; }
    public int costThisTick() { return costThisTick; }
}
