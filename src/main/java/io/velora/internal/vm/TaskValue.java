package io.velora.internal.vm;

import io.velora.api.task.TaskState;

public record TaskValue(long taskId, TaskState state) implements ScriptValue {
    public boolean isNull() { return false; }
    public Object boxed() { return this; }
}
