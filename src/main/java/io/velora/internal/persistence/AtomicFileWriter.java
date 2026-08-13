package io.velora.internal.persistence;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

public final class AtomicFileWriter {

    public static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    public static boolean exists(Path path) {
        return Files.exists(path);
    }

    public static void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
