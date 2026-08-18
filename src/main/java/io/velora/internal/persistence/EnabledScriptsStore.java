package io.velora.internal.persistence;

import io.velora.host.VeloraFileSystem;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

public final class EnabledScriptsStore {
    private static final String ENABLED_FILE = "enabled.velora";
    private final Set<String> enabled = new LinkedHashSet<>();
    private final VeloraFileSystem fileSystem;

    public EnabledScriptsStore() {
        this(null);
    }

    public EnabledScriptsStore(VeloraFileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    public synchronized boolean enable(String scriptId) {
        enabled.add(scriptId);
        return persist();
    }

    public synchronized boolean disable(String scriptId) {
        enabled.remove(scriptId);
        return persist();
    }

    public synchronized boolean isEnabled(String scriptId) {
        return enabled.contains(scriptId);
    }

    public synchronized Set<String> enabledScripts() {
        return Set.copyOf(enabled);
    }

    public synchronized boolean clear() {
        enabled.clear();
        return persist();
    }

    public synchronized boolean load() {
        if (fileSystem == null) return true;
        try {
            byte[] data = fileSystem.readData("", ENABLED_FILE);
            Set<String> loaded = new LinkedHashSet<>();
            if (data != null && data.length > 0) {
                for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
                    String id = line.trim();
                    if (!id.isEmpty()) loaded.add(id);
                }
            }
            enabled.clear();
            enabled.addAll(loaded);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean persist() {
        if (fileSystem == null) return true;
        try {
            fileSystem.writeDataAtomic("", ENABLED_FILE, String.join("\n", enabled).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
