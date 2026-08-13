package io.velora.api.compiler;

public record SourceRange(
        String filePath,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn
) {
    public SourceRange {
        java.util.Objects.requireNonNull(filePath);
        if (startLine < 1) startLine = 1;
        if (startColumn < 1) startColumn = 1;
        if (endLine < startLine) endLine = startLine;
        if (endColumn < startColumn) endColumn = startColumn;
    }

    public static SourceRange of(String filePath, int line, int column) {
        return new SourceRange(filePath, line, column, line, column + 1);
    }

    public static SourceRange of(String filePath, int startLine, int startColumn, int endLine, int endColumn) {
        return new SourceRange(filePath, startLine, startColumn, endLine, endColumn);
    }

    public boolean isSingleLine() {
        return startLine == endLine;
    }

    public String format() {
        if (isSingleLine()) {
            return filePath + ":" + startLine + ":" + startColumn;
        }
        return filePath + ":" + startLine + ":" + startColumn + "-" + endLine + ":" + endColumn;
    }
}
