package io.velora.internal.security;

import java.util.*;

public final class LogRateLimiter {
    private final int maxLogsPerTick;
    private final Map<String, Integer> scriptLogCounts = new HashMap<>();

    public LogRateLimiter(int maxLogsPerTick) {
        this.maxLogsPerTick = maxLogsPerTick;
    }

    public boolean canLog(String scriptId) {
        int count = scriptLogCounts.getOrDefault(scriptId, 0);
        return count < maxLogsPerTick;
    }

    public void recordLog(String scriptId) {
        scriptLogCounts.merge(scriptId, 1, Integer::sum);
    }

    public void resetTick() { scriptLogCounts.clear(); }
}
