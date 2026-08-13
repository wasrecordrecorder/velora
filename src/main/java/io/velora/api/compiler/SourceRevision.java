package io.velora.api.compiler;

public record SourceRevision(
        String scriptId,
        String relativePath,
        String contentHash,
        long modifiedAtNanos
) {
    public SourceRevision {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(relativePath);
        java.util.Objects.requireNonNull(contentHash);
    }

    public static SourceRevision of(String scriptId, String relativePath, String contentHash) {
        return new SourceRevision(scriptId, relativePath, contentHash, System.nanoTime());
    }

    public boolean matches(String hash) {
        return contentHash.equals(hash);
    }
}
