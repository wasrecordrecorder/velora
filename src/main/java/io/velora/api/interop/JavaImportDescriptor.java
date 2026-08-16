package io.velora.api.interop;

import java.nio.file.Path;

public record JavaImportDescriptor(
        String importName,
        String alias,
        String namespace,
        Class<?> type,
        Path source
) { }
