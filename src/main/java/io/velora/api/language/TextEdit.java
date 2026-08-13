package io.velora.api.language;

public record TextEdit(
        String filePath,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        String newText
) {
    public TextEdit {
        java.util.Objects.requireNonNull(filePath);
        java.util.Objects.requireNonNull(newText);
        if (startLine < 1) startLine = 1;
        if (startColumn < 1) startColumn = 1;
        if (endLine < startLine) endLine = startLine;
        if (endColumn < startColumn) endColumn = startColumn;
    }

    public static TextEdit replace(String filePath, int line, int column, int length, String newText) {
        return new TextEdit(filePath, line, column, line, column + length, newText);
    }

    public static TextEdit insert(String filePath, int line, int column, String newText) {
        return new TextEdit(filePath, line, column, line, column, newText);
    }

    public static TextEdit delete(String filePath, int startLine, int startColumn, int endLine, int endColumn) {
        return new TextEdit(filePath, startLine, startColumn, endLine, endColumn, "");
    }
}
