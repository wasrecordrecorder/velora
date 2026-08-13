package io.velora.api.language;

public record DefinitionLocation(
        String filePath,
        int line,
        int column
) {
    public DefinitionLocation {
        java.util.Objects.requireNonNull(filePath);
        if (line < 1) line = 1;
        if (column < 1) column = 1;
    }

    public static DefinitionLocation of(String filePath, int line, int column) {
        return new DefinitionLocation(filePath, line, column);
    }
}
