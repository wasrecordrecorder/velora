package io.velora.internal.event;

import java.util.*;

public final class EventInboundQueue {
    private final int capacity;
    private final Deque<PendingEvent> queue = new ArrayDeque<>();

    public EventInboundQueue(int capacity) {
        this.capacity = capacity;
    }

    public boolean offer(int eventIndex, Object payload) {
        if (queue.size() >= capacity) return false;
        queue.add(new PendingEvent(eventIndex, payload));
        return true;
    }

    public PendingEvent poll() {
        return queue.poll();
    }

    public int size() { return queue.size(); }
    public boolean isEmpty() { return queue.isEmpty(); }
    public void clear() { queue.clear(); }

    public record PendingEvent(int eventIndex, Object payload) {}
}
