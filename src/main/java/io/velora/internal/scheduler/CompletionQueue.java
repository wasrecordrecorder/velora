package io.velora.internal.scheduler;

import io.velora.internal.vm.ScriptValue;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class CompletionQueue {
    private final Queue<CompletionRecord> records = new ConcurrentLinkedQueue<>();

    public record CompletionRecord(long taskId, boolean success, ScriptValue result, Throwable error) {}

    public void offer(long taskId, boolean success, ScriptValue result, Throwable error) {
        records.add(new CompletionRecord(taskId, success, result, error));
    }

    public CompletionRecord poll() {
        return records.poll();
    }

    public boolean isEmpty() { return records.isEmpty(); }
    public int size() { return records.size(); }
    public void clear() { records.clear(); }
}
