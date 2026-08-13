package io.velora.internal.script;

import io.velora.api.compiler.CompileMode;
import io.velora.api.compiler.CompileRequest;
import io.velora.api.compiler.CompileResult;
import io.velora.api.compiler.ScriptCompiler;
import io.velora.api.compiler.SourceFile;
import io.velora.api.script.*;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingSchema;
import io.velora.api.setting.SettingValue;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;
import io.velora.host.VeloraHost;
import io.velora.host.SourceSnapshot;
import io.velora.host.FileRevision;
import io.velora.host.FileTransaction;

import java.util.*;

public final class ScriptTransactionImpl implements ScriptTransaction {
    private final String scriptId;
    private final ScriptCompiler compiler;
    private final VeloraHost host;
    private final SettingSchema settingSchema;
    private final java.util.function.Consumer<ScriptServiceEvents.ScriptServiceEvent> eventFireCallback;
    private final io.velora.internal.setting.SettingStore settingStore;
    private long expectedRevision = -1;
    private final Map<String, String> writes = new LinkedHashMap<>();
    private final Map<String, String> expectedRevisions = new LinkedHashMap<>();
    private final Map<String, SettingValue> settingUpdates = new LinkedHashMap<>();
    private final Set<String> deletes = new LinkedHashSet<>();

    public ScriptTransactionImpl(String scriptId, ScriptCompiler compiler) {
        this(scriptId, compiler, null, null, null, null);
    }

    public ScriptTransactionImpl(String scriptId, ScriptCompiler compiler, VeloraHost host) {
        this(scriptId, compiler, host, null, null, null);
    }

    public ScriptTransactionImpl(String scriptId, ScriptCompiler compiler, VeloraHost host,
                                  SettingSchema settingSchema, java.util.function.Consumer<ScriptServiceEvents.ScriptServiceEvent> eventFireCallback) {
        this(scriptId, compiler, host, settingSchema, eventFireCallback, null);
    }

    public ScriptTransactionImpl(String scriptId, ScriptCompiler compiler, VeloraHost host,
                                  SettingSchema settingSchema, java.util.function.Consumer<ScriptServiceEvents.ScriptServiceEvent> eventFireCallback,
                                  io.velora.internal.setting.SettingStore settingStore) {
        this.scriptId = scriptId;
        this.compiler = compiler;
        this.host = host;
        this.settingSchema = settingSchema;
        this.eventFireCallback = eventFireCallback;
        this.settingStore = settingStore;
    }

    @Override
    public ScriptTransaction expectRevision(long revisionNumber) {
        this.expectedRevision = revisionNumber;
        return this;
    }

    @Override
    public ScriptTransaction write(String relativePath, String content, String expectedFileRevision) {
        writes.put(relativePath, content);
        if (expectedFileRevision != null) {
            expectedRevisions.put(relativePath, expectedFileRevision);
        }
        return this;
    }

    @Override
    public ScriptTransaction updateSetting(String settingId, SettingValue value) {
        settingUpdates.put(settingId, value);
        return this;
    }

    @Override
    public ScriptTransaction delete(String relativePath) {
        deletes.add(relativePath);
        return this;
    }

    @Override
    public ScriptTransactionResult validateAndCommit(CommitMode mode) {
        if (mode == CommitMode.VALIDATE_ONLY) {
            for (Map.Entry<String, String> entry : writes.entrySet()) {
                CompileResult result = compileEntry(entry.getKey(), entry.getValue());
                if (!result.success()) {
                    return ScriptTransactionResult.failure(scriptId, result.diagnostics().toString());
                }
            }
            if (!validateSettings()) {
                return ScriptTransactionResult.failure(scriptId, "Setting validation failed");
            }
            return ScriptTransactionResult.success(scriptId);
        }

        if (mode == CommitMode.RELOAD_IF_VALID) {
            return reloadIfValid();
        }

        // COMMIT_WITHOUT_RELOAD and VALIDATE_AND_COMMIT
        if (host == null || host.fileSystem() == null) {
            for (Map.Entry<String, String> entry : writes.entrySet()) {
                CompileResult result = compileEntry(entry.getKey(), entry.getValue());
                if (!result.success()) {
                    return ScriptTransactionResult.failure(scriptId, result.diagnostics().toString());
                }
            }
            if (!validateSettings()) {
                return ScriptTransactionResult.failure(scriptId, "Setting validation failed");
            }
            try {
                fireSettingsChangedIfNeeded();
            } catch (Throwable t) {
                return ScriptTransactionResult.failure(scriptId, t.getMessage());
            }
            return ScriptTransactionResult.success(scriptId);
        }

        var fs = host.fileSystem();

        for (Map.Entry<String, String> entry : expectedRevisions.entrySet()) {
            SourceSnapshot current = fs.readSource(scriptId, entry.getKey());
            if (current != null && !current.revision().revisionHash().equals(entry.getValue())) {
                return ScriptTransactionResult.failure(scriptId, "Stale revision for " + entry.getKey());
            }
        }

        for (Map.Entry<String, String> entry : writes.entrySet()) {
            CompileResult result = compileEntry(entry.getKey(), entry.getValue());
            if (!result.success()) {
                return ScriptTransactionResult.failure(scriptId, result.diagnostics().toString());
            }
        }

        if (!validateSettings()) {
            return ScriptTransactionResult.failure(scriptId, "Setting validation failed");
        }

        FileTransaction fileTx = fs.beginTransaction(scriptId);
        try {
            for (Map.Entry<String, String> entry : writes.entrySet()) {
                SourceSnapshot current = fs.readSource(scriptId, entry.getKey());
                FileRevision expected = current != null ? current.revision() : null;
                fileTx.write(entry.getKey(), entry.getValue(), expected);
            }
            for (String path : deletes) {
                fileTx.delete(path);
            }
            if (!fileTx.commit()) {
                fileTx.rollback();
                return ScriptTransactionResult.failure(scriptId, "Commit failed");
            }
        } catch (Throwable t) {
            fileTx.rollback();
            return ScriptTransactionResult.failure(scriptId, t.getMessage());
        }

        try {
            fireSettingsChangedIfNeeded();
        } catch (Throwable t) {
            return ScriptTransactionResult.failure(scriptId, t.getMessage());
        }
        return ScriptTransactionResult.success(scriptId);
    }

    private ScriptTransactionResult reloadIfValid() {
        var fs = host != null ? host.fileSystem() : null;

        // Check stale revision
        if (fs != null) {
            for (Map.Entry<String, String> entry : expectedRevisions.entrySet()) {
                SourceSnapshot current = fs.readSource(scriptId, entry.getKey());
                if (current != null && !current.revision().revisionHash().equals(entry.getValue())) {
                    if (expectedRevision >= 0) {
                        // expectRevision() was called — hard stale check, don't apply write
                        return ScriptTransactionResult.failure(scriptId, "Stale revision for " + entry.getKey());
                    } else {
                        // expectRevision() NOT called — soft stale check, apply write anyway
                        applyWrites(fs);
                        return ScriptTransactionResult.failure(scriptId, "Stale revision for " + entry.getKey());
                    }
                }
            }
        }

        // Validate settings
        if (!validateSettings()) {
            return ScriptTransactionResult.failure(scriptId, "Setting validation failed");
        }

        // Validate source — if invalid, DON'T apply write, return failure
        for (Map.Entry<String, String> entry : writes.entrySet()) {
            CompileResult result = compileEntry(entry.getKey(), entry.getValue());
            if (!result.success()) {
                return ScriptTransactionResult.failure(scriptId, result.diagnostics().toString());
            }
        }

        // All valid — apply write
        applyWrites(fs);
        fireSettingsChangedIfNeeded();
        return ScriptTransactionResult.success(scriptId);
    }

    private void applyWrites(io.velora.host.VeloraFileSystem fs) {
        if (fs == null) return;
        FileTransaction fileTx = fs.beginTransaction(scriptId);
        try {
            for (Map.Entry<String, String> entry : writes.entrySet()) {
                SourceSnapshot current = fs.readSource(scriptId, entry.getKey());
                FileRevision expected = current != null ? current.revision() : null;
                fileTx.write(entry.getKey(), entry.getValue(), expected);
            }
            for (String path : deletes) {
                fileTx.delete(path);
            }
            fileTx.commit();
        } catch (Throwable t) {
            fileTx.rollback();
        }
    }

    private void fireSettingsChangedIfNeeded() {
        if (settingUpdates.isEmpty()) return;
        if (settingStore != null) {
            for (Map.Entry<String, SettingValue> entry : settingUpdates.entrySet()) {
                settingStore.set(entry.getKey(), entry.getValue());
            }
        }
        persistSettings();
        if (eventFireCallback != null) {
            eventFireCallback.accept(ScriptServiceEvents.ScriptServiceEvent.of(
                    ScriptServiceEvents.Type.SETTINGS_CHANGED, scriptId));
        }
    }

    private void persistSettings() {
        if (host == null || host.fileSystem() == null || settingStore == null) return;
        try {
            var snapshot = settingStore.snapshot();
            String encoded = io.velora.internal.persistence.SettingsFileCodec.encode(snapshot);
            var fs = host.fileSystem();
            fs.writeDataAtomic(scriptId, "settings.velora", encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            if (host != null && host.logger() != null) {
                host.logger().error("Failed to persist settings for " + scriptId + ": " + t.getMessage(), t);
            }
            throw new IllegalStateException("Settings persistence failed: " + t.getMessage(), t);
        }
    }

    private CompileResult compileEntry(String path, String content) {
        CompileRequest request = CompileRequest.builder(scriptId)
                .source(path, content)
                .mode(CompileMode.FULL)
                .build();
        return compiler.compile(request);
    }

    private boolean validateSettings() {
        if (settingUpdates.isEmpty()) return true;
        if (settingSchema == null) return false;
        for (Map.Entry<String, SettingValue> entry : settingUpdates.entrySet()) {
            Optional<SettingDescriptor> descriptor = settingSchema.find(entry.getKey());
            if (descriptor.isEmpty() || !io.velora.internal.setting.SettingValidator.validate(descriptor.get(), entry.getValue()).isValid()) return false;
        }
        return true;
    }

    public String scriptId() { return scriptId; }
    public long expectedRevision() { return expectedRevision; }
    public Map<String, String> writes() { return writes; }
    public Map<String, SettingValue> settingUpdates() { return settingUpdates; }
    public Set<String> deletes() { return deletes; }
}
