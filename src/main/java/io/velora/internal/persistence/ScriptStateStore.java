package io.velora.internal.persistence;

import java.util.*;

public final class ScriptStateStore {
    private final Map<String, Map<String, Object>> scriptStates = new HashMap<>();

    public void save(String scriptId, Map<String, Object> state) {
        scriptStates.put(scriptId, new LinkedHashMap<>(state));
    }

    public Map<String, Object> load(String scriptId) {
        return scriptStates.getOrDefault(scriptId, Map.of());
    }

    public void clear(String scriptId) {
        scriptStates.remove(scriptId);
    }

    public Set<String> scriptIds() { return Collections.unmodifiableSet(scriptStates.keySet()); }
}
