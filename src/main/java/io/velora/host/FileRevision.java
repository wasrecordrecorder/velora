package io.velora.host;

import java.util.Objects;

public record FileRevision(
        String scriptId,
        String relativePath,
        String revisionHash,
        long revisionNumber
) {
    public FileRevision {
        Objects.requireNonNull(scriptId);
        Objects.requireNonNull(relativePath);
        Objects.requireNonNull(revisionHash);
    }

    public static FileRevision initial(String scriptId, String relativePath, String hash) {
        return new FileRevision(scriptId, relativePath, hash, 1);
    }

    public FileRevision next(String newHash) {
        return new FileRevision(scriptId, relativePath, newHash, revisionNumber + 1);
    }

    public boolean matches(String hash) {
        return revisionHash.equals(hash);
    }
}
