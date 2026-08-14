package io.velora.api.script;

import java.util.List;

public record ScriptDescriptor(
        String id,
        String name,
        String version,
        String author,
        String description,
        ScriptStatus status,
        boolean enabled,
        List<String> sourceFiles,
        ScriptRevision activeRevision,
        int errorCount,
        int warningCount,
        long lastReloadTimeNanos
) {
    public ScriptDescriptor {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(name);
        java.util.Objects.requireNonNull(version);
        java.util.Objects.requireNonNull(status);
        sourceFiles = sourceFiles == null ? List.of() : List.copyOf(sourceFiles);
    }
}
