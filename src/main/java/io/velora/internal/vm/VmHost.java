package io.velora.internal.vm;

import io.velora.api.function.FunctionContext;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.task.VeloraTask;
import io.velora.api.type.VeloraType;

public interface VmHost {
    long nanoTime();
    boolean isCancelled(long fiberId);
    long spawnFiber(String scriptId, int functionIndex, ScriptValue[] args, long parentId);
    void sleepFiber(long fiberId, long wakeupNanos);
    void awaitFiber(long fiberId, long taskId);
    long watchTask(long fiberId, VeloraTask<?> task, VeloraType resultType);
    long watchWorkerCall(long fiberId, FunctionDescriptor descriptor, FunctionContext context);
    boolean consumeApiCost(long fiberId, int cost);
    void yieldFiber(long fiberId);
    ScriptValue loadQualified(String namespace, String member);
    ScriptValue loadField(int fieldIndex);
    void storeField(int fieldIndex, ScriptValue value);
    ScriptValue loadStatic(int fieldIndex);
    void storeStatic(int fieldIndex, ScriptValue value);
}
