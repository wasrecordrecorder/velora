package io.velora.internal.scheduler;

import java.util.*;

public final class SleepQueue {
    private final PriorityQueue<SleepEntry> entries = new PriorityQueue<>(Comparator.comparingLong(SleepEntry::wakeupNanos));

    public record SleepEntry(ScriptFiber fiber, long wakeupNanos) {}

    public void add(ScriptFiber fiber, long wakeupNanos) {
        entries.add(new SleepEntry(fiber, wakeupNanos));
    }

    public List<ScriptFiber> wake(long nowNanos) {
        List<ScriptFiber> woken = new ArrayList<>();
        while (!entries.isEmpty() && entries.peek().wakeupNanos <= nowNanos) {
            woken.add(entries.poll().fiber);
        }
        return woken;
    }

    public boolean isEmpty() { return entries.isEmpty(); }
    public int size() { return entries.size(); }
    public void clear() { entries.clear(); }

    public List<ScriptFiber> removeCancelled(java.util.function.LongPredicate cancelledCheck) {
        List<ScriptFiber> removed = new ArrayList<>();
        entries.removeIf(entry -> {
            if (cancelledCheck.test(entry.fiber().id())) {
                removed.add(entry.fiber());
                return true;
            }
            return false;
        });
        return removed;
    }
}
