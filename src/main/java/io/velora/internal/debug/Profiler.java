package io.velora.internal.debug;

import io.velora.api.debug.ProfilerSnapshot;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Profiler {
    private final Map<String, FunctionProfile> profiles = new HashMap<>();
    private long tickStartTime;
    private long tickEndTime;
    private final AtomicLong totalInstructions = new AtomicLong();
    private final AtomicLong totalApiCalls = new AtomicLong();
    private final AtomicLong totalFailures = new AtomicLong();
    private final AtomicLong totalCancellations = new AtomicLong();
    private final AtomicLong totalApiCost = new AtomicLong();
    private volatile int maxQueueDepth;
    private volatile int droppedEvents;
    private volatile int coalescedEvents;

    public record FunctionProfile(String name, long totalTimeNanos, int callCount) {}

    public void startTick() { tickStartTime = System.nanoTime(); }
    public void endTick() { tickEndTime = System.nanoTime(); }

    public void recordFunction(String name, long timeNanos) {
        FunctionProfile existing = profiles.get(name);
        if (existing != null) {
            profiles.put(name, new FunctionProfile(name, existing.totalTimeNanos() + timeNanos, existing.callCount() + 1));
        } else {
            profiles.put(name, new FunctionProfile(name, timeNanos, 1));
        }
    }

    public void recordInstructions(int count) { totalInstructions.addAndGet(count); }
    public void recordApiCall() { totalApiCalls.incrementAndGet(); }
    public void recordApiCost(long cost) { totalApiCost.addAndGet(cost); }
    public void recordFailure() { totalFailures.incrementAndGet(); }
    public void recordCancellation() { totalCancellations.incrementAndGet(); }
    public void recordMaxQueueDepth(int depth) { if (depth > maxQueueDepth) maxQueueDepth = depth; }
    public void setDroppedEvents(int count) { droppedEvents = count; }
    public void setCoalescedEvents(int count) { coalescedEvents = count; }

    public ProfilerSnapshot snapshot(String scriptId) {
        long tickTime = tickEndTime - tickStartTime;
        return new ProfilerSnapshot(
                scriptId,
                tickTime,
                totalInstructions.get(),
                totalApiCost.get(),
                0, // memoryUsedBytes
                0, // activeFibers
                0, // activeTasks
                0, // eventQueueDepth (current)
                droppedEvents,
                totalApiCalls.get(),
                totalFailures.get(),
                totalCancellations.get(),
                coalescedEvents,
                maxQueueDepth
        );
    }

    public void reset() { profiles.clear(); totalInstructions.set(0); totalApiCalls.set(0); totalFailures.set(0); totalCancellations.set(0); totalApiCost.set(0); maxQueueDepth = 0; droppedEvents = 0; coalescedEvents = 0; }
}
