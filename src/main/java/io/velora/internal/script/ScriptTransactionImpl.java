package io.velora.internal.script;

import io.velora.api.compiler.CompileMode;
import io.velora.api.compiler.CompileRequest;
import io.velora.api.compiler.CompileResult;
import io.velora.api.compiler.ScriptCompiler;
import io.velora.api.compiler.SourceFile;
import io.velora.api.script.ScriptOperationResult;
import io.velora.api.script.ScriptServiceEvents;
import io.velora.api.script.ScriptTransaction;
import io.velora.api.script.ScriptTransactionResult;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingSchema;
import io.velora.api.setting.SettingValue;
import io.velora.host.FileRevision;
import io.velora.host.FileTransaction;
import io.velora.host.SourceSnapshot;
import io.velora.host.VeloraFileSystem;
import io.velora.host.VeloraHost;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.compiler.DefaultScriptCompiler;
import io.velora.internal.persistence.SettingsFileCodec;
import io.velora.internal.setting.SettingStore;
import io.velora.internal.setting.SettingValidator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ScriptTransactionImpl implements ScriptTransaction {
    private final String scriptId;
    private final ScriptCompiler compiler;
    private final VeloraHost host;
    private final SettingSchema settingSchema;
    private final Consumer<ScriptServiceEvents.ScriptServiceEvent> eventFireCallback;
    private final SettingStore settingStore;
    private final long baseRevision;
    private final Runnable sourceRefresh;
    private final Supplier<ScriptOperationResult> reloadCallback;
    private long expectedRevision = -1;
    private final Map<String, String> writes = new LinkedHashMap<>();
    private final Map<String, String> expectedRevisions = new LinkedHashMap<>();
    private final Map<String, SettingValue> settingUpdates = new LinkedHashMap<>();
    private final Set<String> deletes = new LinkedHashSet<>();

    public ScriptTransactionImpl(String scriptId, ScriptCompiler compiler) {
        this(scriptId, compiler, null, null, null, null, -1, null, null);
    }

    public ScriptTransactionImpl(String scriptId, ScriptCompiler compiler, VeloraHost host) {
        this(scriptId, compiler, host, null, null, null, -1, null, null);
    }

    public ScriptTransactionImpl(String scriptId, ScriptCompiler compiler, VeloraHost host,
                                 SettingSchema settingSchema, Consumer<ScriptServiceEvents.ScriptServiceEvent> eventFireCallback) {
        this(scriptId, compiler, host, settingSchema, eventFireCallback, null, -1, null, null);
    }

    public ScriptTransactionImpl(String scriptId, ScriptCompiler compiler, VeloraHost host,
                                 SettingSchema settingSchema, Consumer<ScriptServiceEvents.ScriptServiceEvent> eventFireCallback,
                                 SettingStore settingStore) {
        this(scriptId, compiler, host, settingSchema, eventFireCallback, settingStore, -1, null, null);
    }

    public ScriptTransactionImpl(String scriptId, ScriptCompiler compiler, VeloraHost host,
                                 SettingSchema settingSchema, Consumer<ScriptServiceEvents.ScriptServiceEvent> eventFireCallback,
                                 SettingStore settingStore, long baseRevision, Runnable sourceRefresh,
                                 Supplier<ScriptOperationResult> reloadCallback) {
        this.scriptId = Objects.requireNonNull(scriptId);
        this.compiler = Objects.requireNonNull(compiler);
        this.host = host;
        this.settingSchema = settingSchema;
        this.eventFireCallback = eventFireCallback;
        this.settingStore = settingStore;
        this.baseRevision = baseRevision;
        this.sourceRefresh = sourceRefresh;
        this.reloadCallback = reloadCallback;
    }

    @Override
    public ScriptTransaction expectRevision(long revisionNumber) {
        if (revisionNumber < 0) throw new IllegalArgumentException("Revision must be non-negative");
        expectedRevision = revisionNumber;
        return this;
    }

    @Override
    public ScriptTransaction write(String relativePath, String content, String expectedFileRevision) {
        String path = normalizePath(relativePath);
        Objects.requireNonNull(content, "content");
        writes.put(path, content);
        deletes.remove(path);
        if (expectedFileRevision != null) expectedRevisions.put(path, expectedFileRevision);
        else expectedRevisions.remove(path);
        return this;
    }

    @Override
    public ScriptTransaction updateSetting(String settingId, SettingValue value) {
        settingUpdates.put(Objects.requireNonNull(settingId), Objects.requireNonNull(value));
        return this;
    }

    @Override
    public ScriptTransaction delete(String relativePath) {
        String path = normalizePath(relativePath);
        deletes.add(path);
        writes.remove(path);
        expectedRevisions.remove(path);
        return this;
    }

    @Override
    public ScriptTransactionResult validateAndCommit(CommitMode mode) {
        Objects.requireNonNull(mode, "mode");
        VeloraFileSystem fs = host != null ? host.fileSystem() : null;

        ScriptTransactionResult revisionCheck = validateRevisions(fs);
        if (revisionCheck != null) return revisionCheck;

        SourceValidation sourceValidation = validateSources(fs);
        CompileResult compilation = sourceValidation != null ? sourceValidation.result() : null;
        if (compilation != null && !compilation.success()) {
            return ScriptTransactionResult.conflict(scriptId, ConflictReason.COMPILE_ERROR, compilation.diagnostics().toString());
        }
        SettingStore targetSettings = mode != CommitMode.COMMIT_WITHOUT_RELOAD && sourceValidation != null && sourceValidation.module() != null
                ? migratedStore(sourceValidation.module()) : settingStore;
        if (!validateSettings(targetSettings)) {
            return ScriptTransactionResult.conflict(scriptId, ConflictReason.SETTING_MIGRATION_CONFLICT, "Setting validation failed");
        }
        if (mode == CommitMode.VALIDATE_ONLY) return ScriptTransactionResult.success(scriptId);
        if ((!writes.isEmpty() || !deletes.isEmpty()) && fs == null) {
            return ScriptTransactionResult.failure(scriptId, "File system is unavailable");
        }

        Map<String, SourceSnapshot> sourceSnapshot = fs != null ? snapshotSources(fs) : Map.of();
        Map<String, SettingValue> settingsSnapshot = settingStore != null ? settingStore.snapshot() : Map.of();

        try {
            if (fs != null && (!writes.isEmpty() || !deletes.isEmpty())) {
                ScriptTransactionResult commitResult = commitSources(fs);
                if (commitResult != null) return commitResult;
                refreshSources();
            }
            applySettings(targetSettings);
            persistSettings(targetSettings);

            if (mode == CommitMode.RELOAD_IF_VALID && reloadCallback != null) {
                ScriptOperationResult reload = reloadCallback.get();
                if (reload == null || !reload.success()) {
                    restore(fs, sourceSnapshot, settingsSnapshot);
                    fire(ScriptServiceEvents.Type.ROLLED_BACK, reload != null ? reload.message() : "Reload failed");
                    return ScriptTransactionResult.conflict(scriptId, ConflictReason.ACTIVATION_ERROR,
                            reload != null ? reload.message() : "Reload failed");
                }
            }

            if (!writes.isEmpty() || !deletes.isEmpty()) fire(ScriptServiceEvents.Type.SOURCE_CHANGED, null);
            if (!settingUpdates.isEmpty()) fire(ScriptServiceEvents.Type.SETTINGS_CHANGED, null);
            return ScriptTransactionResult.success(scriptId);
        } catch (Throwable t) {
            restore(fs, sourceSnapshot, settingsSnapshot);
            fire(ScriptServiceEvents.Type.ROLLED_BACK, t.getMessage());
            return ScriptTransactionResult.failure(scriptId, message(t));
        }
    }

    private ScriptTransactionResult validateRevisions(VeloraFileSystem fs) {
        if (expectedRevision >= 0 && baseRevision >= 0 && expectedRevision != baseRevision) {
            return ScriptTransactionResult.conflict(scriptId, ConflictReason.SOURCE_REVISION_CONFLICT,
                    "Expected script revision " + expectedRevision + ", current revision is " + baseRevision);
        }
        if (fs == null) return null;
        for (Map.Entry<String, String> entry : expectedRevisions.entrySet()) {
            SourceSnapshot current = fs.readSource(scriptId, entry.getKey());
            if (current == null || !Objects.equals(current.revision().revisionHash(), entry.getValue())) {
                return ScriptTransactionResult.conflict(scriptId, ConflictReason.SOURCE_REVISION_CONFLICT,
                        "Stale revision for " + entry.getKey());
            }
        }
        return null;
    }

    private SourceValidation validateSources(VeloraFileSystem fs) {
        if (writes.isEmpty() && deletes.isEmpty()) return null;
        Map<String, String> candidate = new LinkedHashMap<>();
        if (fs != null) {
            for (var entry : fs.listScripts()) {
                if (!scriptId.equals(entry.scriptId())) continue;
                SourceSnapshot snapshot = fs.readSource(scriptId, entry.relativePath());
                if (snapshot != null) candidate.put(normalizePath(entry.relativePath()), snapshot.content());
            }
        }
        for (String path : deletes) candidate.remove(path);
        candidate.putAll(writes);

        List<SourceFile> sources = candidate.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".vls"))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new SourceFile(entry.getKey(), entry.getValue(), null))
                .toList();
        CompileRequest request = sources.isEmpty()
                ? CompileRequest.builder(scriptId).source("main.vls", "").mode(CompileMode.FULL).build()
                : CompileRequest.builder(scriptId).sources(sources).mode(CompileMode.FULL).build();
        CompileResult result = compiler.compile(request);
        CompiledModule module = null;
        if (result.success() && compiler instanceof DefaultScriptCompiler defaultCompiler) {
            CompileRequest cached = new CompileRequest(request.scriptId(), request.sources(), CompileMode.INCREMENTAL, request.languageVersion(), request.options());
            module = defaultCompiler.compileToModule(cached);
        }
        return new SourceValidation(result, module);
    }

    private ScriptTransactionResult commitSources(VeloraFileSystem fs) {
        FileTransaction tx = fs.beginTransaction(scriptId);
        try {
            for (Map.Entry<String, String> entry : writes.entrySet()) {
                String expectedHash = expectedRevisions.get(entry.getKey());
                FileRevision expected;
                if (expectedHash != null) {
                    expected = new FileRevision(scriptId, entry.getKey(), expectedHash, -1);
                } else {
                    SourceSnapshot current = fs.readSource(scriptId, entry.getKey());
                    expected = current != null ? current.revision() : null;
                }
                tx.write(entry.getKey(), entry.getValue(), expected);
            }
            for (String path : deletes) {
                SourceSnapshot current = fs.readSource(scriptId, path);
                if (current != null) tx.validateExpectedRevision(path, current.revision());
                tx.delete(path);
            }
            if (!tx.commit()) {
                tx.rollback();
                return ScriptTransactionResult.conflict(scriptId, ConflictReason.SOURCE_REVISION_CONFLICT, "Source changed before transaction commit");
            }
            return null;
        } catch (Throwable t) {
            tx.rollback();
            return ScriptTransactionResult.failure(scriptId, message(t));
        }
    }

    private Map<String, SourceSnapshot> snapshotSources(VeloraFileSystem fs) {
        Map<String, SourceSnapshot> result = new LinkedHashMap<>();
        for (var entry : fs.listScripts()) {
            if (!scriptId.equals(entry.scriptId())) continue;
            SourceSnapshot snapshot = fs.readSource(scriptId, entry.relativePath());
            if (snapshot != null) result.put(normalizePath(entry.relativePath()), snapshot);
        }
        return result;
    }

    private void restore(VeloraFileSystem fs, Map<String, SourceSnapshot> sources, Map<String, SettingValue> settings) {
        if (settingStore != null) settingStore.applySnapshot(settings);
        if (fs != null && (!writes.isEmpty() || !deletes.isEmpty())) restoreSources(fs, sources);
        try {
            persistSettings(settingStore);
        } catch (Throwable t) {
            logRollbackFailure("settings", t);
        }
        refreshSources();
    }

    private void restoreSources(VeloraFileSystem fs, Map<String, SourceSnapshot> sources) {
        FileTransaction tx = fs.beginTransaction(scriptId);
        try {
            boolean changed = false;
            for (Map.Entry<String, String> entry : writes.entrySet()) {
                String path = entry.getKey();
                SourceSnapshot current = fs.readSource(scriptId, path);
                if (current == null || !Objects.equals(current.content(), entry.getValue())) {
                    logRollbackFailure("sources", new IllegalStateException("Source changed after commit: " + path));
                    continue;
                }
                SourceSnapshot original = sources.get(path);
                if (original != null) tx.write(path, original.content(), current.revision());
                else {
                    tx.validateExpectedRevision(path, current.revision());
                    tx.delete(path);
                }
                changed = true;
            }
            for (String path : deletes) {
                SourceSnapshot current = fs.readSource(scriptId, path);
                if (current != null) {
                    logRollbackFailure("sources", new IllegalStateException("Deleted source was recreated after commit: " + path));
                    continue;
                }
                SourceSnapshot original = sources.get(path);
                if (original != null) {
                    tx.write(path, original.content(), null);
                    changed = true;
                }
            }
            if (!changed) {
                tx.rollback();
                return;
            }
            if (!tx.commit()) {
                tx.rollback();
                throw new IllegalStateException("Rollback file transaction failed");
            }
        } catch (Throwable t) {
            tx.rollback();
            logRollbackFailure("sources", t);
        }
    }

    private SettingStore migratedStore(CompiledModule module) {
        SettingStore migrated = new SettingStore(module.settings());
        if (settingStore != null) migrated.applySnapshot(settingStore.snapshot());
        return migrated;
    }

    private void applySettings(SettingStore store) {
        if (settingUpdates.isEmpty()) return;
        if (store == null) throw new IllegalStateException("Setting store is unavailable");
        for (Map.Entry<String, SettingValue> entry : settingUpdates.entrySet()) store.set(entry.getKey(), entry.getValue());
    }

    private void persistSettings(SettingStore store) {
        if (settingUpdates.isEmpty() || host == null || host.fileSystem() == null || store == null) return;
        String encoded = SettingsFileCodec.encode(store.snapshot());
        host.fileSystem().writeDataAtomic(scriptId, "settings.velora", encoded.getBytes(StandardCharsets.UTF_8));
    }

    private boolean validateSettings(SettingStore store) {
        if (settingUpdates.isEmpty()) return true;
        if (store == null) return false;
        SettingSchema schema = new SettingSchema(store.descriptors());
        for (Map.Entry<String, SettingValue> entry : settingUpdates.entrySet()) {
            Optional<SettingDescriptor> descriptor = schema.find(entry.getKey());
            if (descriptor.isEmpty() || !SettingValidator.validate(descriptor.get(), entry.getValue()).isValid()) return false;
        }
        return true;
    }

    private record SourceValidation(CompileResult result, CompiledModule module) {}

    private void refreshSources() {
        if (sourceRefresh != null) sourceRefresh.run();
    }

    private void fire(ScriptServiceEvents.Type type, String message) {
        if (eventFireCallback == null) return;
        eventFireCallback.accept(message == null
                ? ScriptServiceEvents.ScriptServiceEvent.of(type, scriptId)
                : ScriptServiceEvents.ScriptServiceEvent.of(type, scriptId, message));
    }

    private void logRollbackFailure(String target, Throwable t) {
        if (host != null && host.logger() != null) host.logger().error("Failed to rollback " + target + " for " + scriptId + ": " + message(t), t);
    }

    private static String normalizePath(String path) {
        Objects.requireNonNull(path, "relativePath");
        String normalized = path.replace('\\', '/').trim();
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0 || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Invalid relative path: " + path);
        }
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/+")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IllegalArgumentException("Path traversal is not allowed: " + path);
            parts.add(part);
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("Invalid relative path: " + path);
        return String.join("/", parts);
    }

    private static String message(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    public String scriptId() { return scriptId; }
    public long expectedRevision() { return expectedRevision; }
    public Map<String, String> writes() { return Map.copyOf(writes); }
    public Map<String, SettingValue> settingUpdates() { return Map.copyOf(settingUpdates); }
    public Set<String> deletes() { return Set.copyOf(deletes); }
}
