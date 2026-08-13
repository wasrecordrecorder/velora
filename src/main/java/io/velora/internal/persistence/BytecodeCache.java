package io.velora.internal.persistence;

import io.velora.internal.bytecode.CompiledModule;

import java.util.HashMap;
import java.util.Map;

public final class BytecodeCache {
    private final Map<Key, CompiledModule> cache = new HashMap<>();

    public CompiledModule get(String scriptId, String sourceHash, String registryHash) {
        return cache.get(new Key(scriptId, sourceHash, registryHash));
    }

    public void put(String scriptId, String sourceHash, String registryHash, CompiledModule module) {
        invalidate(scriptId);
        cache.put(new Key(scriptId, sourceHash, registryHash), module);
    }

    public void invalidate(String scriptId) {
        cache.keySet().removeIf(key -> key.scriptId.equals(scriptId));
    }

    public void clear() { cache.clear(); }

    private record Key(String scriptId, String sourceHash, String registryHash) {}
}
