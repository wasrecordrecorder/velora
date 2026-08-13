package io.velora.api.compiler;

public record SourceFile(
        String relativePath,
        String content,
        String contentHash
) {
    public SourceFile {
        java.util.Objects.requireNonNull(relativePath);
        java.util.Objects.requireNonNull(content);
        if (contentHash == null || contentHash.isEmpty()) {
            contentHash = io.velora.internal.source.SourceHash.compute(content);
        }
    }

    public static SourceFile of(String relativePath, String content) {
        return new SourceFile(relativePath, content, null);
    }
}
