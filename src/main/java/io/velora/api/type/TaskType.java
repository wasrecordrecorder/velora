package io.velora.api.type;

import io.velora.api.task.VeloraTask;

import java.util.Objects;

final class TaskType implements VeloraType {
    private final VeloraType result;
    private final boolean nullable;

    TaskType(VeloraType result) {
        this(result, false);
    }

    private TaskType(VeloraType result, boolean nullable) {
        this.result = Objects.requireNonNull(result);
        this.nullable = nullable;
    }

    VeloraType result() { return result; }
    @Override public String name() { return "Task<" + result.name() + ">" + (nullable ? "?" : ""); }
    @Override public boolean isHashable() { return false; }
    @Override public boolean isNullable() { return nullable; }
    @Override public Class<?> javaClass() { return VeloraTask.class; }
    @Override public VeloraType nullable() { return nullable ? this : new TaskType(result, true); }
    @Override public VeloraType nonNull() { return nullable ? new TaskType(result, false) : this; }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TaskType task && nullable == task.nullable && result.equals(task.result);
    }

    @Override public int hashCode() { return Objects.hash(result, nullable); }
}
