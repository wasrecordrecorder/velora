package io.velora.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class LocalVeloraFileSystem implements VeloraFileSystem {
    private static final String DATA_DIR = ".velora";
    private final Path root;

    public LocalVeloraFileSystem(Path root) {
        Path normalized = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized);
            this.root = normalized.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create scripts root: " + normalized, e);
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public List<ScriptFileEntry> listScripts() {
        if (!Files.isDirectory(root)) return List.of();
        List<ScriptFileEntry> result = new ArrayList<>();
        try (var scripts = Files.list(root)) {
            for (Path scriptDir : scripts.filter(path -> Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)).filter(path -> !Files.isSymbolicLink(path)).filter(path -> !path.getFileName().toString().equals(DATA_DIR)).toList()) {
                String scriptId = scriptDir.getFileName().toString();
                if (!isScriptId(scriptId)) continue;
                try (var files = Files.walk(scriptDir)) {
                    for (Path file : files.filter(Files::isRegularFile).filter(path -> isSource(scriptDir, path)).toList()) {
                        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                        result.add(new ScriptFileEntry(scriptId, relative(scriptDir, file), attrs.size(), attrs.lastModifiedTime().toMillis() * 1_000_000L));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list scripts in " + root, e);
        }
        result.sort(Comparator.comparing(ScriptFileEntry::scriptId).thenComparing(ScriptFileEntry::relativePath));
        return List.copyOf(result);
    }

    @Override
    public SourceSnapshot readSource(String scriptId, String relativePath) {
        Path file = sourcePath(scriptId, relativePath);
        if (!Files.isRegularFile(file)) return null;
        try {
            byte[] bytes = Files.readAllBytes(file);
            String content = new String(bytes, StandardCharsets.UTF_8);
            String hash = hash(bytes);
            long modified = Files.getLastModifiedTime(file).toMillis();
            return new SourceSnapshot(content, new FileRevision(scriptId, normalizeSourcePath(relativePath), hash, Math.max(1, modified)), hash, modified * 1_000_000L);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read source " + scriptId + "/" + relativePath, e);
        }
    }

    @Override
    public FileRevision writeAtomic(String scriptId, String relativePath, String content, FileRevision expectedRevision) {
        Objects.requireNonNull(content, "content");
        String path = normalizeSourcePath(relativePath);
        if (!matches(scriptId, path, expectedRevision)) throw new IllegalStateException("Revision conflict for " + scriptId + "/" + path);
        Path target = sourcePath(scriptId, path);
        writeAtomic(target, content.getBytes(StandardCharsets.UTF_8));
        return Objects.requireNonNull(readSource(scriptId, path)).revision();
    }

    @Override
    public FileTransaction beginTransaction(String scriptId) {
        requireScriptId(scriptId);
        return new Transaction(scriptId);
    }

    @Override
    public byte[] readData(String scriptId, String key) {
        Path path = dataPath(scriptId, key);
        if (!Files.isRegularFile(path)) return null;
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read data " + key + " for " + scriptId, e);
        }
    }

    @Override
    public void writeDataAtomic(String scriptId, String key, byte[] data) {
        writeAtomic(dataPath(scriptId, key), Objects.requireNonNull(data, "data"));
    }

    @Override
    public boolean scriptExists(String scriptId) {
        return Files.isDirectory(scriptDir(scriptId), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void deleteScript(String scriptId) {
        Path dir = scriptDir(scriptId);
        if (!Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete script " + scriptId, e);
        }
    }

    private Path scriptDir(String scriptId) {
        requireScriptId(scriptId);
        Path path = resolveSafe(root, scriptId);
        rejectSymlinkEscape(root, path);
        return path;
    }

    private Path sourcePath(String scriptId, String relativePath) {
        Path path = resolveSafe(scriptDir(scriptId), normalizeSourcePath(relativePath));
        rejectSymlinkEscape(root, path);
        return path;
    }

    private Path dataPath(String scriptId, String key) {
        String normalized = normalizeRelative(key, false);
        Path base = scriptId == null || scriptId.isEmpty() ? resolveSafe(root, DATA_DIR, "global") : resolveSafe(scriptDir(scriptId), DATA_DIR);
        Path path = resolveSafe(base, normalized);
        rejectSymlinkEscape(root, path);
        return path;
    }

    private boolean matches(String scriptId, String path, FileRevision expected) {
        if (expected == null) return !Files.exists(sourcePath(scriptId, path));
        SourceSnapshot current = readSource(scriptId, path);
        return current != null && Objects.equals(current.revision().revisionHash(), expected.revisionHash());
    }

    private static String normalizeSourcePath(String path) {
        String normalized = normalizeRelative(path, true);
        if (!normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".vls")) throw new IllegalArgumentException("Source file must end with .vls: " + path);
        return normalized;
    }

    private static String normalizeRelative(String value, boolean rejectDataDir) {
        Objects.requireNonNull(value, "path");
        String normalized = value.replace('\\', '/').trim();
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0 || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("Invalid relative path: " + value);
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/+")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IllegalArgumentException("Path traversal is not allowed: " + value);
            if (rejectDataDir && part.equals(DATA_DIR)) throw new IllegalArgumentException("Reserved path segment: " + DATA_DIR);
            parts.add(part);
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("Invalid relative path: " + value);
        return String.join("/", parts);
    }

    private static boolean isSource(Path scriptDir, Path path) {
        if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".vls")) return false;
        Path relative = scriptDir.relativize(path);
        for (Path part : relative) if (part.toString().equals(DATA_DIR)) return false;
        return true;
    }

    private static String relative(Path base, Path path) {
        return base.relativize(path).toString().replace('\\', '/');
    }

    private static Path resolveSafe(Path base, String... parts) {
        Path result = base;
        for (String part : parts) result = result.resolve(part);
        result = result.normalize();
        if (!result.startsWith(base.normalize())) throw new IllegalArgumentException("Path escapes scripts root");
        return result;
    }

    private static void rejectSymlinkEscape(Path base, Path target) {
        Path current = base;
        Path relative = base.relativize(target);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current) && Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symbolic links are not allowed inside script storage: " + current);
        }
    }

    private static void requireScriptId(String scriptId) {
        if (!isScriptId(scriptId)) throw new IllegalArgumentException("Invalid script id: " + scriptId);
    }

    private static boolean isScriptId(String scriptId) {
        if (scriptId == null || scriptId.isBlank() || scriptId.equals(".") || scriptId.equals("..")) return false;
        for (int i = 0; i < scriptId.length(); i++) {
            char c = scriptId.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') return false;
        }
        return true;
    }

    private static String hash(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeAtomic(Path target, byte[] data) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".velora-", ".tmp");
            try {
                Files.write(temp, data);
                moveReplace(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + target, e);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private final class Transaction implements FileTransaction {
        private final String scriptId;
        private final Map<String, Write> writes = new LinkedHashMap<>();
        private final Map<String, FileRevision> checks = new LinkedHashMap<>();
        private final Set<String> deletes = new LinkedHashSet<>();
        private boolean committed;

        private Transaction(String scriptId) {
            this.scriptId = scriptId;
        }

        @Override
        public String scriptId() {
            return scriptId;
        }

        @Override
        public FileTransaction write(String relativePath, String content, FileRevision expectedRevision) {
            ensureOpen();
            String path = normalizeSourcePath(relativePath);
            writes.put(path, new Write(Objects.requireNonNull(content, "content"), expectedRevision));
            deletes.remove(path);
            return this;
        }

        @Override
        public FileTransaction delete(String relativePath) {
            ensureOpen();
            String path = normalizeSourcePath(relativePath);
            deletes.add(path);
            writes.remove(path);
            return this;
        }

        @Override
        public FileTransaction validateExpectedRevision(String relativePath, FileRevision expected) {
            ensureOpen();
            checks.put(normalizeSourcePath(relativePath), Objects.requireNonNull(expected, "expected"));
            return this;
        }

        @Override
        public boolean commit() {
            ensureOpen();
            for (Map.Entry<String, FileRevision> check : checks.entrySet()) if (!matches(scriptId, check.getKey(), check.getValue())) return false;
            for (Map.Entry<String, Write> write : writes.entrySet()) if (!matches(scriptId, write.getKey(), write.getValue().expected())) return false;

            Path transactionRoot = resolveSafe(root, DATA_DIR, "transactions", UUID.randomUUID().toString());
            Map<String, Path> staged = new LinkedHashMap<>();
            Map<String, Path> backups = new LinkedHashMap<>();
            Set<String> existing = new LinkedHashSet<>();
            try {
                rejectSymlinkEscape(root, transactionRoot);
                Files.createDirectories(transactionRoot);
                for (Map.Entry<String, Write> write : writes.entrySet()) {
                    Path stagedFile = resolveSafe(transactionRoot, "stage", write.getKey());
                    Files.createDirectories(stagedFile.getParent());
                    Files.writeString(stagedFile, write.getValue().content(), StandardCharsets.UTF_8);
                    staged.put(write.getKey(), stagedFile);
                }

                Set<String> affected = new LinkedHashSet<>(deletes);
                affected.addAll(writes.keySet());
                for (String path : affected) {
                    Path target = sourcePath(scriptId, path);
                    if (!Files.exists(target)) continue;
                    existing.add(path);
                    Path backup = resolveSafe(transactionRoot, "backup", path);
                    Files.createDirectories(backup.getParent());
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    backups.put(path, backup);
                }

                for (String path : deletes) Files.deleteIfExists(sourcePath(scriptId, path));
                for (Map.Entry<String, Path> entry : staged.entrySet()) {
                    Path target = sourcePath(scriptId, entry.getKey());
                    Files.createDirectories(target.getParent());
                    moveReplace(entry.getValue(), target);
                }
                committed = true;
                cleanup(transactionRoot);
                return true;
            } catch (Throwable error) {
                try {
                    Set<String> affected = new LinkedHashSet<>(deletes);
                    affected.addAll(writes.keySet());
                    for (String path : affected) {
                        Path target = sourcePath(scriptId, path);
                        if (existing.contains(path)) {
                            Path backup = backups.get(path);
                            if (backup != null && Files.exists(backup)) {
                                Files.createDirectories(target.getParent());
                                Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            }
                        } else {
                            Files.deleteIfExists(target);
                        }
                    }
                } catch (IOException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                cleanup(transactionRoot);
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("File transaction failed", error);
            }
        }

        @Override
        public void rollback() {
            if (committed) return;
            writes.clear();
            checks.clear();
            deletes.clear();
        }

        @Override
        public boolean isCommitted() {
            return committed;
        }

        private void ensureOpen() {
            if (committed) throw new IllegalStateException("Transaction is already committed");
        }

        private record Write(String content, FileRevision expected) {}
    }

    private static void cleanup(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }
}
