package io.velora.internal.debug;

import io.velora.api.debug.*;
import io.velora.internal.scheduler.ScriptScheduler;

import java.util.*;

public final class DefaultDebugService implements DebugService {

    private final ScriptLogStore logStore;
    private final RuntimeErrorStore errorStore;
    private final Profiler profiler;
    private final ScriptScheduler scheduler;

    public DefaultDebugService(ScriptLogStore logStore, RuntimeErrorStore errorStore,
                               Profiler profiler, ScriptScheduler scheduler) {
        this.logStore = logStore;
        this.errorStore = errorStore;
        this.profiler = profiler;
        this.scheduler = scheduler;
    }

    @Override
    public List<ScriptLogEntry> logs(String scriptId) {
        return logStore.get(scriptId);
    }

    @Override
    public List<RuntimeError> errors(String scriptId) {
        return errorStore.get(scriptId);
    }

    @Override
    public ProfilerSnapshot profiler(String scriptId) {
        ProfilerSnapshot base = profiler.snapshot(scriptId);
        var resources = scheduler.resources(scriptId);
        return new ProfilerSnapshot(scriptId, scheduler.tickTimeNanos(scriptId), scheduler.instructionsForScript(scriptId),
                scheduler.totalApiCost(scriptId), resources.memoryUsed(), resources.fibers(), resources.tasks(),
                resources.eventQueueSize() + base.eventQueueDepth(), base.droppedEvents(), scheduler.apiCalls(scriptId), scheduler.failures(scriptId),
                scheduler.cancellations(scriptId), base.coalescedEvents(), base.maxQueueDepth());
    }

    @Override
    public List<FiberSnapshot> fibers(String scriptId) {
        return FiberInspector.inspectAll(scheduler.fibersForScript(scriptId));
    }

    @Override
    public List<TaskSnapshot> tasks(String scriptId) {
        List<TaskSnapshot> result = new ArrayList<>();
        for (var task : scheduler.tasksForScript(scriptId)) {
            result.add(new TaskSnapshot(
                System.identityHashCode(task),
                scriptId,
                task.state().name(),
                task.getClass().getSimpleName(),
                scheduler.nanoTime()
            ));
        }
        return result;
    }

    @Override
    public DebugSnapshot snapshot(String scriptId) {
        return new DebugSnapshot(scriptId, logs(scriptId), errors(scriptId),
                profiler(scriptId), fibers(scriptId), tasks(scriptId));
    }

    @Override
    public void clearLogs(String scriptId) {
        logStore.clear(scriptId);
    }

    @Override
    public void terminateFiber(String scriptId, long fiberId) {
        scheduler.cancelFiber(fiberId);
    }
}
