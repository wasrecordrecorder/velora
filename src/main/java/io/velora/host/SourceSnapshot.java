package io.velora.host;

import java.util.Objects;

public record SourceSnapshot(
        String content,
        FileRevision revision,
        String contentHash,
        long modifiedAtNanos
) {
    public SourceSnapshot {
        Objects.requireNonNull(content);
        Objects.requireNonNull(revision);
        Objects.requireNonNull(contentHash);
    }

    public static SourceSnapshot of(String content, FileRevision revision, String contentHash) {
        return new SourceSnapshot(content, revision, contentHash, System.nanoTime());
    }
}
