package io.velora.api.interop;

import java.nio.file.Path;

public record JavaImportDescriptor(
        String importName,
        String alias,
        String namespace,
        Class<?> type,
        Path source,
        String description
) {
    public JavaImportDescriptor {
        description = description == null ? "" : description;
    }

    public JavaImportDescriptor(String importName, String alias, String namespace, Class<?> type, Path source) {
        this(importName, alias, namespace, type, source, "");
    }
}
