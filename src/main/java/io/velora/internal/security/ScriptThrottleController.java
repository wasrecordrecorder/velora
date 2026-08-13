package io.velora.internal.security;

import java.util.*;

public final class ScriptThrottleController {
    private final Map<String, Integer> violations = new HashMap<>();
    private final Set<String> throttled = new HashSet<>();
    private final int maxViolations;

    public ScriptThrottleController(int maxViolations) {
        this.maxViolations = maxViolations;
    }

    public void recordViolation(String scriptId) {
        int count = violations.merge(scriptId, 1, Integer::sum);
        if (count >= maxViolations) {
            throttled.add(scriptId);
        }
    }

    public boolean isThrottled(String scriptId) {
        return throttled.contains(scriptId);
    }

    public void unthrottle(String scriptId) {
        throttled.remove(scriptId);
        violations.remove(scriptId);
    }

    public void clear(String scriptId) {
        throttled.remove(scriptId);
        violations.remove(scriptId);
    }

    public Set<String> throttledScripts() { return Set.copyOf(throttled); }
}
