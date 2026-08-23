package io.velora.host;

import io.velora.api.data.ByteArray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class LocalAssetStore implements AssetStore {
    private final Path root;
    private final Path legacyRoot;
    private final long maxAssetBytes;
    private final VeloraLogger logger;
    private final Set<String> warnedLegacyPaths = new HashSet<>();

    LocalAssetStore(Path root, Path legacyRoot, long maxAssetBytes, VeloraLogger logger) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.legacyRoot = legacyRoot == null ? null : legacyRoot.toAbsolutePath().normalize();
        this.maxAssetBytes = maxAssetBytes;
        this.logger = Objects.requireNonNull(logger, "logger");
        if (maxAssetBytes <= 0) throw new IllegalArgumentException("Maximum asset size must be positive");
        try {
            Files.createDirectories(this.root);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to create asset directory: " + this.root, error);
        }
    }

    @Override
    public boolean exists(String path) {
        Path primary = resolve(root, path, false);
        if (safeRegularFile(root, primary)) return true;
        return legacyRoot != null && safeRegularFile(legacyRoot, resolve(legacyRoot, path, false));
    }

    @Override
    public ByteArray bytes(String path) {
        Path file = locate(path);
        try {
            long size = Files.size(file);
            if (size > maxAssetBytes) throw new IllegalStateException("Asset exceeds " + maxAssetBytes + " bytes: " + path);
            return ByteArray.copyOf(Files.readAllBytes(file));
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read asset: " + path, error);
        }
    }

    @Override
    public String text(String path) {
        return new String(bytes(path).toArray(), StandardCharsets.UTF_8);
    }

    @Override
    public List<String> list(String directory) {
        Path base = resolve(root, directory, true);
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || !insideRealRoot(root, base)) return List.of();
        List<String> result = new ArrayList<>();
        try (var stream = Files.list(base)) {
            stream.filter(path -> !Files.isSymbolicLink(path)).forEach(path -> result.add(root.relativize(path).toString().replace('\\', '/')));
        } catch (IOException error) {
            throw new IllegalStateException("Failed to list assets: " + directory, error);
        }
        result.sort(String::compareToIgnoreCase);
        return List.copyOf(result);
    }

    private Path locate(String path) {
        Path primary = resolve(root, path, false);
        if (safeRegularFile(root, primary)) return primary;
        if (legacyRoot != null) {
            Path legacy = resolve(legacyRoot, path, false);
            if (safeRegularFile(legacyRoot, legacy)) {
                synchronized (warnedLegacyPaths) {
                    String normalized = root.relativize(primary).toString().replace('\\', '/');
                    if (warnedLegacyPaths.add(normalized)) logger.warn("Legacy script asset path used: " + normalized + "; move it to assets/");
                }
                return legacy;
            }
        }
        throw new IllegalArgumentException("Asset does not exist: " + path);
    }

    private static Path resolve(Path base, String value, boolean emptyAllowed) {
        String path = Objects.requireNonNull(value, "path").trim().replace('\\', '/');
        if (path.indexOf('\0') >= 0 || !emptyAllowed && path.isEmpty()) throw new IllegalArgumentException("Invalid asset path");
        Path relative = Path.of(path.isEmpty() ? "." : path);
        if (relative.isAbsolute()) throw new IllegalArgumentException("Asset path must be relative: " + value);
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) throw new IllegalArgumentException("Asset path escapes the asset directory: " + value);
        return resolved;
    }

    private static boolean safeRegularFile(Path root, Path file) {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && insideRealRoot(root, file);
    }

    private static boolean insideRealRoot(Path root, Path path) {
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException error) {
            return false;
        }
    }
}
