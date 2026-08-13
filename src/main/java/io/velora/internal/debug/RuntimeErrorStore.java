package io.velora.internal.debug;

import io.velora.api.debug.RuntimeError;

import java.util.*;

public final class RuntimeErrorStore {
    private final Map<String, List<RuntimeError>> errors = new HashMap<>();
    private final int maxErrorsPerScript;

    public RuntimeErrorStore(int maxErrorsPerScript) {
        this.maxErrorsPerScript = maxErrorsPerScript;
    }

    public void record(String scriptId, RuntimeError error) {
        List<RuntimeError> list = errors.computeIfAbsent(scriptId, k -> new ArrayList<>());
        list.add(error);
        while (list.size() > maxErrorsPerScript) list.remove(0);
    }

    public List<RuntimeError> get(String scriptId) {
        return List.copyOf(errors.getOrDefault(scriptId, List.of()));
    }

    public void clear(String scriptId) { errors.remove(scriptId); }
    public void clearAll() { errors.clear(); }
}
