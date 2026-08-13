package io.velora.api.language;

public record HoverInfo(
        String content,
        String filePath,
        int line,
        int column
) {
    public HoverInfo {
        java.util.Objects.requireNonNull(content);
    }

    public static HoverInfo of(String content, String filePath, int line, int column) {
        return new HoverInfo(content, filePath, line, column);
    }
}
