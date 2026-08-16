package io.velora.api.interop;

import java.nio.file.Path;

public record JavaImportDescriptor(
        String importName,
        String alias,
        String namespace,
        String runtimeClassName,
        Path source
) { }
