package io.velora.internal.debug;

import io.velora.api.debug.*;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ScriptLogStore {
    private final Map<String, Deque<ScriptLogEntry>> logs = new HashMap<>();
    private final int maxEntriesPerScript;

    public ScriptLogStore(int maxEntriesPerScript) {
        this.maxEntriesPerScript = maxEntriesPerScript;
    }

    public void log(String scriptId, ScriptLogEntry entry) {
        logs.computeIfAbsent(scriptId, k -> new ArrayDeque<>()).addLast(entry);
        Deque<ScriptLogEntry> q = logs.get(scriptId);
        while (q.size() > maxEntriesPerScript) q.pollFirst();
    }

    public List<ScriptLogEntry> get(String scriptId) {
        Deque<ScriptLogEntry> q = logs.get(scriptId);
        return q != null ? List.copyOf(q) : List.of();
    }

    public void clear(String scriptId) { logs.remove(scriptId); }
    public void clearAll() { logs.clear(); }
}
