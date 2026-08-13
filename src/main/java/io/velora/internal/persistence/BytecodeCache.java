package io.velora.internal.persistence;

import io.velora.internal.bytecode.CompiledModule;

import java.util.*;

public final class BytecodeCache {
    private final Map<String, CompiledModule> cache = new HashMap<>();
    private final Map<String, String> sourceHashes = new HashMap<>();

    public CompiledModule get(String scriptId, String sourceHash) {
        String cachedHash = sourceHashes.get(scriptId);
        if (sourceHash != null && sourceHash.equals(cachedHash)) {
            return cache.get(scriptId);
        }
        return null;
    }

    public void put(String scriptId, String sourceHash, CompiledModule module) {
        cache.put(scriptId, module);
        sourceHashes.put(scriptId, sourceHash);
    }

    public void invalidate(String scriptId) {
        cache.remove(scriptId);
        sourceHashes.remove(scriptId);
    }

    public void clear() {
        cache.clear();
        sourceHashes.clear();
    }
}
