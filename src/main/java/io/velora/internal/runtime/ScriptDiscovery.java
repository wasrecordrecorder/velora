package io.velora.internal.runtime;

import io.velora.host.VeloraHost;
import io.velora.host.ScriptFileEntry;

import java.util.*;

public final class ScriptDiscovery {

    private final VeloraHost host;

    public ScriptDiscovery(VeloraHost host) {
        this.host = host;
    }

    public List<ScriptFileEntry> discover() {
        if (host.fileSystem() == null) return List.of();
        try {
            return host.fileSystem().listScripts();
        } catch (Throwable t) {
            return List.of();
        }
    }

    public Map<String, String> readScript(String scriptId) {
        if (host.fileSystem() == null) return Map.of();
        try {
            var snapshot = host.fileSystem().readSource(scriptId, "main.vls");
            return Map.of("main.vls", snapshot != null ? snapshot.content() : "");
        } catch (Throwable t) {
            return Map.of();
        }
    }
}
