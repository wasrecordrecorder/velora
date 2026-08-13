package io.velora.internal.persistence;

import io.velora.host.VeloraFileSystem;
import java.util.*;

public final class EnabledScriptsStore {
    private final Set<String> enabled = new LinkedHashSet<>();
    private final VeloraFileSystem fileSystem;
    private static final String ENABLED_FILE = "enabled.velora";

    public EnabledScriptsStore() {
        this(null);
    }

    public EnabledScriptsStore(VeloraFileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    public void enable(String scriptId) {
        enabled.add(scriptId);
        persist();
    }

    public void disable(String scriptId) {
        enabled.remove(scriptId);
        persist();
    }

    public boolean isEnabled(String scriptId) {
        return enabled.contains(scriptId);
    }

    public Set<String> enabledScripts() {
        return Set.copyOf(enabled);
    }

    public void clear() {
        enabled.clear();
        persist();
    }

    public void load() {
        if (fileSystem == null) return;
        try {
            byte[] data = fileSystem.readData("", ENABLED_FILE);
            if (data != null && data.length > 0) {
                String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                for (String line : content.split("\n")) {
                    String id = line.trim();
                    if (!id.isEmpty()) {
                        enabled.add(id);
                    }
                }
            }
        } catch (Throwable ignored) {
            // best-effort load
        }
    }

    private void persist() {
        if (fileSystem == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (String id : enabled) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(id);
            }
            fileSystem.writeDataAtomic("", ENABLED_FILE, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // best-effort persist
        }
    }
}
