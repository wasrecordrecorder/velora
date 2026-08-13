package io.velora.api.debug;

import java.util.List;

public record DebugSnapshot(
        String scriptId,
        List<ScriptLogEntry> logs,
        List<RuntimeError> errors,
        ProfilerSnapshot profiler,
        List<FiberSnapshot> fibers,
        List<TaskSnapshot> tasks
) {
    public DebugSnapshot {
        java.util.Objects.requireNonNull(scriptId);
        logs = logs == null ? List.of() : List.copyOf(logs);
        errors = errors == null ? List.of() : List.copyOf(errors);
        fibers = fibers == null ? List.of() : List.copyOf(fibers);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        profiler = profiler == null ? ProfilerSnapshot.empty(scriptId) : profiler;
    }

    public static DebugSnapshot empty(String scriptId) {
        return new DebugSnapshot(scriptId, List.of(), List.of(), null, List.of(), List.of());
    }
}
