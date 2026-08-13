package io.velora.internal.scheduler;

import java.util.*;

public final class FiberQueue {
    private final Deque<ScriptFiber> ready = new ArrayDeque<>();

    public void add(ScriptFiber fiber) {
        ready.add(fiber);
    }

    public ScriptFiber poll() {
        return ready.poll();
    }

    public boolean isEmpty() { return ready.isEmpty(); }
    public int size() { return ready.size(); }
    public void clear() { ready.clear(); }
    public List<ScriptFiber> snapshot() { return List.copyOf(ready); }
}
