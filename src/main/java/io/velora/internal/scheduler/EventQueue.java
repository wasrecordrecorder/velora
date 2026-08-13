package io.velora.internal.scheduler;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class EventQueue {
    private final int capacity;
    private final Queue<PendingEvent> queue = new ConcurrentLinkedQueue<>();

    public EventQueue(int capacity) {
        this.capacity = capacity;
    }

    public record PendingEvent(int eventIndex, Object payload) {}

    public boolean offer(int eventIndex, Object payload) {
        if (queue.size() >= capacity) return false;
        queue.add(new PendingEvent(eventIndex, payload));
        return true;
    }

    public PendingEvent poll() { return queue.poll(); }
    public boolean isEmpty() { return queue.isEmpty(); }
    public int size() { return queue.size(); }
    public void clear() { queue.clear(); }
}
