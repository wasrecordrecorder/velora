package io.velora.internal.debug;

import io.velora.api.debug.ProfilerSnapshot;

import java.util.HashMap;
import java.util.Map;

public final class Profiler {
    private final Map<String, Integer> droppedEvents = new HashMap<>();
    private final Map<String, Long> coalescedEvents = new HashMap<>();
    private final Map<String, Integer> currentQueueDepth = new HashMap<>();
    private final Map<String, Integer> maxQueueDepth = new HashMap<>();

    public void recordDropped(String scriptId) {
        recordDropped(scriptId, 1);
    }

    public void recordDropped(String scriptId, int count) {
        if (count > 0) droppedEvents.merge(scriptId, count, Integer::sum);
    }

    public void recordCoalesced(String scriptId) {
        coalescedEvents.merge(scriptId, 1L, Long::sum);
    }

    public void recordQueueDepth(String scriptId, int depth) {
        int normalized = Math.max(0, depth);
        if (normalized == 0) currentQueueDepth.remove(scriptId);
        else currentQueueDepth.put(scriptId, normalized);
        maxQueueDepth.merge(scriptId, normalized, Math::max);
    }

    public ProfilerSnapshot snapshot(String scriptId) {
        return new ProfilerSnapshot(scriptId, 0, 0, 0, 0, 0, 0, currentQueueDepth.getOrDefault(scriptId, 0),
                droppedEvents.getOrDefault(scriptId, 0), 0, 0, 0,
                coalescedEvents.getOrDefault(scriptId, 0L), maxQueueDepth.getOrDefault(scriptId, 0));
    }

}
