package io.velora.internal.source;

import java.util.Objects;

public record SourceUnit(
        String scriptId,
        String relativePath,
        String content,
        String contentHash,
        LineMap lineMap
) {
    public SourceUnit {
        Objects.requireNonNull(scriptId);
        Objects.requireNonNull(relativePath);
        Objects.requireNonNull(content);
        Objects.requireNonNull(contentHash);
        lineMap = lineMap != null ? lineMap : LineMap.of(content);
    }

    public static SourceUnit of(String scriptId, String relativePath, String content) {
        String hash = SourceHash.compute(content);
        return new SourceUnit(scriptId, relativePath, content, hash, LineMap.of(content));
    }

    public int lineCount() {
        return lineMap.lineCount();
    }

    public String lineText(int line) {
        return lineMap.lineText(content, line);
    }

    public int offsetOf(int line, int column) {
        return lineMap.offsetOf(line, column);
    }
}
