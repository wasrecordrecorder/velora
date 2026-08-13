package io.velora.host;

import java.util.Objects;

public record ScriptFileEntry(
        String scriptId,
        String relativePath,
        long sizeBytes,
        long modifiedAtNanos
) {
    public ScriptFileEntry {
        Objects.requireNonNull(scriptId);
        Objects.requireNonNull(relativePath);
    }
}
