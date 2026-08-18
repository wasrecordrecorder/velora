package io.velora.internal.persistence;

import io.velora.internal.bytecode.CompiledModule;

import java.util.HashMap;
import java.util.Map;

public final class BytecodeCache {
    private final Map<Key, CompiledModule> cache = new HashMap<>();

    public synchronized CompiledModule get(String scriptId, String sourceHash, String registryHash) {
        return cache.get(new Key(scriptId, sourceHash, registryHash));
    }

    public synchronized void put(String scriptId, String sourceHash, String registryHash, CompiledModule module) {
        invalidate(scriptId);
        cache.put(new Key(scriptId, sourceHash, registryHash), module);
    }

    public synchronized void invalidate(String scriptId) {
        cache.keySet().removeIf(key -> key.scriptId.equals(scriptId));
    }

    public synchronized void clear() { cache.clear(); }

    private record Key(String scriptId, String sourceHash, String registryHash) {}
}
