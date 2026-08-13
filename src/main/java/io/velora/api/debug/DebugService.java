package io.velora.api.debug;

public interface DebugService {

    java.util.List<ScriptLogEntry> logs(String scriptId);

    java.util.List<RuntimeError> errors(String scriptId);

    ProfilerSnapshot profiler(String scriptId);

    java.util.List<FiberSnapshot> fibers(String scriptId);

    java.util.List<TaskSnapshot> tasks(String scriptId);

    DebugSnapshot snapshot(String scriptId);

    void clearLogs(String scriptId);

    void terminateFiber(String scriptId, long fiberId);
}
