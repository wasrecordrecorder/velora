package io.velora.internal.scheduler;

import java.util.*;

public final class CancellationTree {
    private final Map<Long, Set<Long>> children = new HashMap<>();
    private final Set<Long> cancelled = new HashSet<>();

    public void addChild(long parentId, long childId) {
        children.computeIfAbsent(parentId, k -> new HashSet<>()).add(childId);
    }

    public void cancel(long fiberId) {
        cancelled.add(fiberId);
        Set<Long> kids = children.get(fiberId);
        if (kids != null) {
            for (Long child : kids) cancel(child);
        }
    }

    public boolean isCancelled(long fiberId) {
        return cancelled.contains(fiberId);
    }

    public void remove(long fiberId, long parentId) {
        cancelled.remove(fiberId);
        children.remove(fiberId);
        Set<Long> siblings = children.get(parentId);
        if (siblings != null) {
            siblings.remove(fiberId);
            if (siblings.isEmpty()) children.remove(parentId);
        }
    }

    public void clear() {
        children.clear();
        cancelled.clear();
    }
}
