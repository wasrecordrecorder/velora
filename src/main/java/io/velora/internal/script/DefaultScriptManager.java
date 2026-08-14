package io.velora.internal.script;

import io.velora.api.compiler.*;
import io.velora.api.script.*;
import io.velora.api.registry.PermissionRegistry;
import io.velora.api.setting.SettingSchema;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.type.VeloraTypes;
import io.velora.host.ScriptFileEntry;
import io.velora.host.VeloraHost;
import io.velora.host.SourceSnapshot;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.compiler.DefaultScriptCompiler;
import io.velora.internal.scheduler.ScriptScheduler;
import io.velora.internal.setting.SettingStore;
import io.velora.internal.security.PermissionController;
import io.velora.internal.persistence.EnabledScriptsStore;
import io.velora.internal.vm.ScriptValue;

import java.util.*;

public final class DefaultScriptManager implements ScriptManager {

    private final ScriptRepository repository = new ScriptRepository();
    private final ScriptRevisionManager revisionManager = new ScriptRevisionManager();
    private final ScriptServiceEventBus eventBus = new ScriptServiceEventBus();
    private final ScriptServiceEventsImpl serviceEvents = new ScriptServiceEventsImpl();
    private final ScriptScheduler scheduler;
    private final VeloraHost host;
    private final ScriptCompiler compiler;
    private final Map<String, ScriptRuntime> runtimes = new HashMap<>();
    private final Map<String, io.velora.api.permission.PermissionSet> grants = new HashMap<>();
    private final PermissionController permissionController;
    private final EnabledScriptsStore enabledScriptsStore;
    private final io.velora.api.debug.DebugService debugService;
    private final PermissionRegistry permissionRegistry;
    private final ScriptTemplateRegistry templateRegistry;

    public DefaultScriptManager(ScriptScheduler scheduler, ScriptCompiler compiler) {
        this(scheduler, compiler, null, null, null, null, null, null);
    }

    public DefaultScriptManager(ScriptScheduler scheduler, ScriptCompiler compiler, VeloraHost host) {
        this(scheduler, compiler, host, null, null, null, null, null);
    }

    public DefaultScriptManager(ScriptScheduler scheduler, ScriptCompiler compiler, VeloraHost host,
                                PermissionController permissionController, EnabledScriptsStore enabledScriptsStore,
                                io.velora.api.debug.DebugService debugService, PermissionRegistry permissionRegistry,
                                ScriptTemplateRegistry templateRegistry) {
        this.scheduler = scheduler;
        this.compiler = compiler;
        this.host = host;
        this.permissionController = permissionController;
        this.enabledScriptsStore = enabledScriptsStore;
        this.debugService = debugService;
        this.permissionRegistry = permissionRegistry;
        this.templateRegistry = templateRegistry;
    }

    @Override
    public List<ScriptDescriptor> list() {
        return repository.descriptors();
    }

    @Override
    public Optional<ScriptHandle> find(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        if (instance == null) return Optional.empty();
        return Optional.of(new ScriptHandleImpl(instance));
    }

    @Override
    public ScriptOperationResult create(ScriptCreateRequest request) {
        Objects.requireNonNull(request, "request");
        String scriptId = request.scriptId();
        if (host == null || host.fileSystem() == null) return ScriptOperationResult.failure(scriptId, "File system is unavailable");
        if (repository.contains(scriptId) || host.fileSystem().scriptExists(scriptId)) return ScriptOperationResult.failure(scriptId, "Script already exists");

        Map<String, String> files = new LinkedHashMap<>();
        if (request.templateId() != null) {
            if (templateRegistry == null) return ScriptOperationResult.failure(scriptId, "Script template registry is unavailable");
            ScriptTemplate template = templateRegistry.find(request.templateId()).orElse(null);
            if (template == null) return ScriptOperationResult.failure(scriptId, "Unknown script template: " + request.templateId());
            files.putAll(template.files());
        }
        files.putAll(request.initialFiles());
        if (files.isEmpty()) return ScriptOperationResult.failure(scriptId, "At least one .vls source file is required");

        List<SourceFile> sources;
        try {
            sources = files.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> new SourceFile(entry.getKey(), entry.getValue(), null)).toList();
        } catch (RuntimeException error) {
            return ScriptOperationResult.failure(scriptId, error.getMessage(), error);
        }
        CompileResult validation = compiler.compile(CompileRequest.builder(scriptId).sources(sources).mode(CompileMode.FULL).build());
        if (!validation.success()) return ScriptOperationResult.failure(scriptId, validation.diagnostics().toString());

        var fs = host.fileSystem();
        var fileTx = fs.beginTransaction(scriptId);
        try {
            for (var entry : files.entrySet()) fileTx.write(entry.getKey(), entry.getValue(), null);
            if (!fileTx.commit()) return ScriptOperationResult.failure(scriptId, "Failed to create script files");
        } catch (Throwable error) {
            fileTx.rollback();
            return ScriptOperationResult.failure(scriptId, error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName(), error);
        }

        List<String> sourceFiles = files.keySet().stream().sorted().toList();
        ScriptDescriptor descriptor = new ScriptDescriptor(scriptId, request.name(), "1.0.0", null, null, ScriptStatus.DISCOVERED, false,
                sourceFiles, io.velora.api.permission.PermissionSet.empty(), null, 0, 0, 0);
        ScriptInstance instance = ScriptInstanceFactory.create(scriptId, descriptor);
        repository.register(instance);
        loadGrants(scriptId);
        compileInstance(instance);
        if (instance.compiledModule() == null) {
            repository.remove(scriptId);
            fs.deleteScript(scriptId);
            return ScriptOperationResult.failure(scriptId, "Compilation failed after script creation");
        }

        if (!request.initialSettings().isEmpty()) {
            ScriptTransaction transaction = beginTransaction(scriptId);
            for (var entry : request.initialSettings().entrySet()) {
                SettingDescriptor setting = settings(scriptId).find(entry.getKey()).orElse(null);
                if (setting == null) {
                    repository.remove(scriptId);
                    fs.deleteScript(scriptId);
                    return ScriptOperationResult.failure(scriptId, "Unknown initial setting: " + entry.getKey());
                }
                transaction.updateSetting(entry.getKey(), io.velora.api.setting.SettingValue.of(setting.type(), entry.getValue()));
            }
            ScriptTransactionResult settingsResult = transaction.validateAndCommit(ScriptTransaction.CommitMode.COMMIT_WITHOUT_RELOAD);
            if (!settingsResult.success()) {
                repository.remove(scriptId);
                fs.deleteScript(scriptId);
                return ScriptOperationResult.failure(scriptId, settingsResult.message());
            }
        }

        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.CREATED, scriptId, instance.status()));
        return ScriptOperationResult.success(scriptId, instance.status());
    }

    @Override
    public ScriptOperationResult delete(String scriptId) {
        Objects.requireNonNull(scriptId, "scriptId");
        if (host == null || host.fileSystem() == null) return ScriptOperationResult.failure(scriptId, "File system is unavailable");
        ScriptInstance instance = repository.get(scriptId);
        boolean exists = instance != null || host.fileSystem().scriptExists(scriptId);
        if (!exists) return ScriptOperationResult.failure(scriptId, "Script not found");

        if (instance != null) {
            if (instance.enabled()) {
                ScriptOperationResult disabled = disable(scriptId);
                if (!disabled.success()) return disabled;
            }
            ScriptOperationResult unloaded = unload(scriptId);
            if (!unloaded.success()) return unloaded;
        }
        if (enabledScriptsStore != null) enabledScriptsStore.disable(scriptId);
        grants.remove(scriptId);
        try {
            host.fileSystem().deleteScript(scriptId);
        } catch (Throwable error) {
            return ScriptOperationResult.failure(scriptId, error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName(), error);
        }
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.DELETED, scriptId, ScriptStatus.UNLOADED));
        return ScriptOperationResult.success(scriptId, ScriptStatus.UNLOADED);
    }

    @Override
    public ScriptOperationResult enable(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        if (instance == null) return ScriptOperationResult.failure(scriptId, "Script not found");
        if (instance.enabled()) return ScriptOperationResult.failure(scriptId, "Script already enabled");
        
        if (instance.compiledModule() == null) {
            return ScriptOperationResult.failure(scriptId, "Script not compiled");
        }
        
        // Check permission grants
        io.velora.api.permission.PermissionSet required = instance.compiledModule().requiredPermissions();
        if (required != null && !required.isEmpty()) {
            io.velora.api.permission.PermissionSet granted = grants.getOrDefault(scriptId, io.velora.api.permission.PermissionSet.empty());
            if (!granted.containsAll(required)) {
                return ScriptOperationResult.failure(scriptId, "Permission denied: required permissions not granted");
            }
        }
        
        // Load persistent fields before enabling
        loadPersistentFields(instance);
        
        ScriptRuntime runtime = new ScriptRuntime(instance, scheduler);
        runtimes.put(scriptId, runtime);
        instance.statusMachine().transition(ScriptStatus.ENABLING);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.STATUS_CHANGED, scriptId, ScriptStatus.ENABLING));
        
        CompiledModule module = instance.compiledModule();
        Map<String, CompiledModule> mods = schedulerModules();
        Map<String, List<io.velora.api.setting.SettingDescriptor>> sets = schedulerSettings();
        if (instance.settingStore() != null) {
            scheduler.setSettingStore(scriptId, instance.settingStore());
        }
        // Only call ON_LOAD and ON_ENABLE synchronously during enable.
        // ON_RUN is spawned asynchronously by runtime.start().
        for (String hook : module.lifecycleHooks()) {
            if (hook.equals("ON_DISABLE") || hook.equals("ON_UNLOAD") || hook.equals("ON_RUN") || hook.equals("ON_TICK")) continue;
            int fnIdx = findFunctionIndex(module, hook);
            if (fnIdx >= 0) {
                scheduler.spawnFiberAndAwait(instance.scriptId(), fnIdx, new ScriptValue[0], mods, sets);
            }
        }
        
        if (!runtime.start()) {
            runtimes.remove(scriptId);
            scheduler.stopScript(scriptId);
            RuntimeException error = new RuntimeException("Unable to start script: runtime resource limit rejected ON_RUN");
            instance.lastError(error);
            instance.statusMachine().transition(ScriptStatus.FAILED);
            if (enabledScriptsStore != null) enabledScriptsStore.disable(scriptId);
            serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.RUNTIME_ERROR, scriptId, error.getMessage()));
            return ScriptOperationResult.failure(scriptId, error.getMessage(), error);
        }
        instance.statusMachine().transition(ScriptStatus.ENABLED);
        if (enabledScriptsStore != null) enabledScriptsStore.enable(scriptId);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.ENABLED, scriptId, ScriptStatus.ENABLED));
        return ScriptOperationResult.success(scriptId, ScriptStatus.ENABLED);
    }
    
    private int findFunctionIndex(CompiledModule module, String name) {
        for (int i = 0; i < module.functions().size(); i++) {
            if (module.function(i).name().equals(name)) return i;
        }
        return -1;
    }

    @Override
    public ScriptOperationResult disable(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        if (instance == null) return ScriptOperationResult.failure(scriptId, "Script not found");
        ScriptRuntime runtime = runtimes.remove(scriptId);
        if (runtime != null) {
            CompiledModule module = instance.compiledModule();
            if (module != null) {
                if (instance.settingStore() != null) {
                    scheduler.setSettingStore(scriptId, instance.settingStore());
                }
                int fnIdx = findFunctionIndex(module, "ON_DISABLE");
                if (fnIdx >= 0) {
                    scheduler.spawnFiberAndAwait(instance.scriptId(), fnIdx, new ScriptValue[0], schedulerModules(), schedulerSettings());
                }
            }
            runtime.stop();
        }
        // Save persistent fields before disabling
        boolean saveOk = savePersistentFields(instance);
        instance.statusMachine().transition(ScriptStatus.DISABLING);
        instance.statusMachine().transition(ScriptStatus.DISABLED);
        if (enabledScriptsStore != null) enabledScriptsStore.disable(scriptId);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.DISABLED, scriptId, ScriptStatus.DISABLED));
        if (!saveOk) {
            return ScriptOperationResult.failure(scriptId, "State persistence failed");
        }
        return ScriptOperationResult.success(scriptId, ScriptStatus.DISABLED);
    }

    @Override
    public ScriptOperationResult toggle(String scriptId) {
        if (isEnabled(scriptId)) return disable(scriptId);
        return enable(scriptId);
    }

    @Override
    public ScriptOperationResult reload(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        if (instance == null) return ScriptOperationResult.failure(scriptId, "Script not found");
        CompiledModule candidate = compileModule(instance);
        if (candidate == null) return ScriptOperationResult.failure(scriptId, "Compilation failed");

        boolean wasEnabled = instance.enabled();
        if (wasEnabled && !hasPermissionGrant(scriptId, candidate.requiredPermissions())) {
            return ScriptOperationResult.failure(scriptId, "Permission denied: reloaded script requires permissions that are not granted");
        }

        CompiledModule oldModule = instance.compiledModule();
        SettingStore oldSettings = instance.settingStore();
        ScriptDescriptor oldDescriptor = instance.descriptor();
        long oldRevision = instance.revision();

        if (wasEnabled) {
            ScriptOperationResult disabled = disable(scriptId);
            if (!disabled.success()) {
                if (!instance.enabled()) enable(scriptId);
                return ScriptOperationResult.failure(scriptId, "Failed to disable current script before reload: " + disabled.message(), disabled.cause());
            }
        }
        if (oldModule != null) invokeLifecycle(instance, oldModule, "ON_UNLOAD");

        instance.statusMachine().transition(ScriptStatus.RELOADING);
        instance.compiledModule(candidate);
        instance.settingStore(createSettingStore(candidate));
        loadSettingsFromDisk(instance);
        long newRevision = oldRevision + 1;
        instance.revision(newRevision);
        ScriptRevision revision = new ScriptRevision(scriptId, newRevision, candidate.sourceHash(), nanoTime());
        instance.descriptor(descriptorForModule(oldDescriptor, candidate, revision, nanoTime()));
        instance.statusMachine().transition(ScriptStatus.LOADED);

        if (wasEnabled) {
            ScriptOperationResult enabled = enable(scriptId);
            if (!enabled.success()) {
                instance.compiledModule(oldModule);
                instance.settingStore(oldSettings);
                instance.revision(oldRevision);
                instance.descriptor(oldDescriptor);
                instance.statusMachine().transition(ScriptStatus.LOADED);
                ScriptOperationResult restored = enable(scriptId);
                serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.ROLLED_BACK, scriptId, enabled.message()));
                if (!restored.success()) return ScriptOperationResult.failure(scriptId, "Reload activation failed and previous runtime could not be restored: " + restored.message(), restored.cause());
                return ScriptOperationResult.failure(scriptId, "Reload activation failed: " + enabled.message(), enabled.cause());
            }
        }
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.RELOADED, scriptId));
        return ScriptOperationResult.success(scriptId, instance.status());
    }

    private void invokeLifecycle(ScriptInstance instance, CompiledModule module, String hook) {
        int functionIndex = findFunctionIndex(module, hook);
        if (functionIndex < 0) return;
        if (instance.settingStore() != null) scheduler.setSettingStore(instance.scriptId(), instance.settingStore());
        scheduler.spawnFiberAndAwait(instance.scriptId(), functionIndex, new ScriptValue[0], schedulerModules(), schedulerSettings());
    }

    private Map<String, CompiledModule> schedulerModules() {
        Map<String, CompiledModule> result = new HashMap<>();
        for (ScriptInstance value : repository.all()) if (value.compiledModule() != null) result.put(value.scriptId(), value.compiledModule());
        return result;
    }

    private Map<String, List<io.velora.api.setting.SettingDescriptor>> schedulerSettings() {
        Map<String, List<io.velora.api.setting.SettingDescriptor>> result = new HashMap<>();
        for (ScriptInstance value : repository.all()) if (value.compiledModule() != null) result.put(value.scriptId(), value.compiledModule().settings());
        return result;
    }

    private SettingStore createSettingStore(CompiledModule module) {
        List<SettingDescriptor> settingsList = new ArrayList<>(module.settings());
        if (settingsList.isEmpty()) {
            settingsList.add(new SettingDescriptor("enabled", "Enabled", VeloraTypes.BOOLEAN, Boolean.TRUE,
                    null, "Whether the script is enabled", null, 0, false, false, false,
                    null, List.of(), 0));
        }
        return new SettingStore(settingsList);
    }

    private ScriptDescriptor descriptorForModule(ScriptDescriptor current, CompiledModule module, ScriptRevision revision, long reloadTime) {
        return new ScriptDescriptor(current.id(), module.scriptName() != null ? module.scriptName() : current.name(),
                module.version() != null ? module.version() : current.version(),
                module.author() != null ? module.author() : current.author(),
                module.description() != null ? module.description() : current.description(),
                ScriptStatus.LOADED, false, current.sourceFiles(), module.requiredPermissions(), revision,
                current.errorCount(), current.warningCount(), reloadTime);
    }

    @Override
    public ScriptOperationResult unload(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        if (instance == null) return ScriptOperationResult.failure(scriptId, "Script not found");
        // Stop runtime and save persistent fields, but do NOT update enabledScriptsStore
        // (unload is engine shutdown, not user-initiated disable)
        ScriptRuntime runtime = runtimes.remove(scriptId);
        if (runtime != null) {
            CompiledModule module = instance.compiledModule();
            if (module != null) {
                if (instance.settingStore() != null) {
                    scheduler.setSettingStore(scriptId, instance.settingStore());
                }
                int fnIdx = findFunctionIndex(module, "ON_DISABLE");
                if (fnIdx >= 0) {
                    scheduler.spawnFiberAndAwait(instance.scriptId(), fnIdx, new ScriptValue[0], schedulerModules(), schedulerSettings());
                }
            }
            runtime.stop();
        }
        savePersistentFields(instance);
        CompiledModule module = instance.compiledModule();
        if (module != null) {
            if (instance.settingStore() != null) {
                scheduler.setSettingStore(scriptId, instance.settingStore());
            }
            int fnIdx = findFunctionIndex(module, "ON_UNLOAD");
            if (fnIdx >= 0) {
                scheduler.spawnFiberAndAwait(instance.scriptId(), fnIdx, new ScriptValue[0], schedulerModules(), schedulerSettings());
            }
        }
        scheduler.cleanupScript(scriptId);
        instance.statusMachine().transition(ScriptStatus.UNLOADED);
        repository.remove(scriptId);
        runtimes.remove(scriptId);
        revisionManager.clear(scriptId);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.STATUS_CHANGED, scriptId, ScriptStatus.UNLOADED));
        return ScriptOperationResult.success(scriptId, ScriptStatus.UNLOADED);
    }

    @Override
    public boolean isEnabled(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        return instance != null && instance.enabled();
    }

    @Override
    public ScriptStatus status(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        return instance != null ? instance.status() : ScriptStatus.UNLOADED;
    }

    @Override
    public SettingSchema settings(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        if (instance == null || instance.compiledModule() == null) return new SettingSchema(List.of());
        if (instance.settingStore() != null) {
            return new SettingSchema(instance.settingStore().descriptors());
        }
        return instance.compiledModule().settingSchema();
    }

    @Override
    public Map<String, io.velora.api.setting.SettingValue> settingValues(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        return instance != null && instance.settingStore() != null ? Map.copyOf(instance.settingStore().snapshot()) : Map.of();
    }

    @Override
    public ScriptTransaction beginTransaction(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        if (instance == null) throw new IllegalArgumentException("Script not found: " + scriptId);
        return new ScriptTransactionImpl(scriptId, compiler, host, settings(scriptId), serviceEvents::fire,
                instance.settingStore(), instance.revision(), () -> refreshSourceFiles(instance), () -> reload(scriptId));
    }

    @Override
    public ScriptServiceEvents events() {
        return serviceEvents;
    }

    @Override
    public void discover() {
        if (enabledScriptsStore != null) enabledScriptsStore.load();
        if (host == null || host.fileSystem() == null) return;
        Map<String, List<ScriptFileEntry>> scripts = new LinkedHashMap<>();
        for (ScriptFileEntry entry : host.fileSystem().listScripts()) {
            scripts.computeIfAbsent(entry.scriptId(), ignored -> new ArrayList<>()).add(entry);
        }
        for (var entry : scripts.entrySet()) {
            String scriptId = entry.getKey();
            if (repository.get(scriptId) != null) continue;
            List<String> sourceFiles = entry.getValue().stream().map(ScriptFileEntry::relativePath).distinct().sorted().toList();
            ScriptDescriptor descriptor = new ScriptDescriptor(scriptId, scriptId, "1.0.0", null, null,
                    ScriptStatus.DISCOVERED, false, sourceFiles, io.velora.api.permission.PermissionSet.empty(),
                    null, 0, 0, 0);
            ScriptInstance instance = ScriptInstanceFactory.create(scriptId, descriptor);
            repository.register(instance);
            loadGrants(scriptId);
            serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.DISCOVERED, scriptId));
            compileInstance(instance);
        }
    }

    private void refreshSourceFiles(ScriptInstance instance) {
        if (host == null || host.fileSystem() == null) return;
        List<String> paths = host.fileSystem().listScripts().stream()
                .filter(entry -> entry.scriptId().equals(instance.scriptId()))
                .map(ScriptFileEntry::relativePath).distinct().sorted().toList();
        ScriptDescriptor current = instance.descriptor();
        instance.descriptor(new ScriptDescriptor(current.id(), current.name(), current.version(), current.author(), current.description(),
                current.status(), current.enabled(), paths, current.permissions(), current.activeRevision(),
                current.errorCount(), current.warningCount(), current.lastReloadTimeNanos()));
    }

    private CompiledModule compileModule(ScriptInstance instance) {
        if (host == null || host.fileSystem() == null || !(compiler instanceof DefaultScriptCompiler defaultCompiler)) return null;
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.COMPILE_STARTED, instance.scriptId()));
        List<SourceFile> sources = new ArrayList<>();
        for (String path : instance.descriptor().sourceFiles()) {
            SourceSnapshot snapshot = host.fileSystem().readSource(instance.scriptId(), path);
            if (snapshot != null) sources.add(new SourceFile(path, snapshot.content(), snapshot.contentHash()));
        }
        CompiledModule module = sources.isEmpty() ? null : defaultCompiler.compileToModule(CompileRequest.builder(instance.scriptId()).sources(sources).mode(CompileMode.FULL).build());
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.COMPILE_FINISHED, instance.scriptId()));
        return module;
    }

    private void compileInstance(ScriptInstance instance) {
        CompiledModule module = compileModule(instance);
        if (module != null) {
            instance.compiledModule(module);
            instance.settingStore(createSettingStore(module));
            instance.revision(1);
            loadSettingsFromDisk(instance);
            instance.statusMachine().transition(ScriptStatus.LOADED);
            ScriptDescriptor d = instance.descriptor();
            ScriptRevision rev = ScriptRevision.initial(instance.scriptId(), module.sourceHash());
            String name = module.scriptName() != null ? module.scriptName() : d.name();
            String version = module.version() != null ? module.version() : d.version();
            String author = module.author() != null ? module.author() : d.author();
            String description = module.description() != null ? module.description() : d.description();
            instance.descriptor(new ScriptDescriptor(
                d.id(), name, version, author, description,
                instance.status(), instance.enabled(),
                d.sourceFiles(), module.requiredPermissions(), rev,
                d.errorCount(), d.warningCount(), d.lastReloadTimeNanos()
            ));
        }
    }

    private void loadSettingsFromDisk(ScriptInstance instance) {
        if (host == null || host.fileSystem() == null || instance.settingStore() == null) return;
        try {
            var fs = host.fileSystem();
            byte[] data = fs.readData(instance.scriptId(), "settings.velora");
            if (data != null && data.length > 0) {
                String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                var loaded = io.velora.internal.persistence.SettingsFileCodec.decode(content);
                int applied = instance.settingStore().applySnapshot(loaded);
                if (applied != loaded.size() && host.logger() != null) host.logger().warn("Ignored invalid persisted settings for " + instance.scriptId());
            }
        } catch (Throwable t) {
            if (host.logger() != null) host.logger().warn("Failed to load settings for " + instance.scriptId() + ": " + t.getMessage());
        }
    }

    private boolean savePersistentFields(ScriptInstance instance) {
        if (host == null || host.fileSystem() == null || instance.compiledModule() == null) return true;
        CompiledModule module = instance.compiledModule();
        List<String> persistentIds = module.persistentFieldIds();
        if (persistentIds.isEmpty()) return true;
        List<Integer> indices = module.persistentFieldIndices();
        List<Boolean> isStatic = module.persistentFieldIsStatic();
        if (indices.size() != persistentIds.size() || isStatic.size() != persistentIds.size()) return true;
        
        Map<String, Object> state = new LinkedHashMap<>();
        for (int i = 0; i < persistentIds.size(); i++) {
            String pid = persistentIds.get(i);
            int idx = indices.get(i);
            boolean staticField = isStatic.get(i);
            ScriptValue value;
            if (staticField) {
                value = scheduler.loadStaticForScript(instance.scriptId(), idx);
            } else {
                value = scheduler.loadFieldForScript(instance.scriptId(), idx);
            }
            state.put(pid, value == null || value.isNull() ? null : value.boxed());
        }
        try {
            String encoded = io.velora.internal.persistence.StateFileCodec.encode(state);
            host.fileSystem().writeDataAtomic(instance.scriptId(), "state.bin", encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            if (host.logger() != null) host.logger().error("Failed to persist state for " + instance.scriptId() + ": " + t.getMessage(), t);
            return false;
        }
        return true;
    }

    private void loadPersistentFields(ScriptInstance instance) {
        if (host == null || host.fileSystem() == null || instance.compiledModule() == null) return;
        CompiledModule module = instance.compiledModule();
        List<String> persistentIds = module.persistentFieldIds();
        if (persistentIds.isEmpty()) return;
        List<Integer> indices = module.persistentFieldIndices();
        List<Boolean> isStatic = module.persistentFieldIsStatic();
        if (indices.size() != persistentIds.size() || isStatic.size() != persistentIds.size()) return;
        
        try {
            byte[] data = host.fileSystem().readData(instance.scriptId(), "state.bin");
            if (data == null || data.length == 0) return;
            String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> state = io.velora.internal.persistence.StateFileCodec.decode(content);
            for (int i = 0; i < persistentIds.size(); i++) {
                String pid = persistentIds.get(i);
                int idx = indices.get(i);
                boolean staticField = isStatic.get(i);
                if (!state.containsKey(pid)) continue;
                ScriptValue value = persistentValue(state.get(pid), module.persistentFieldTypes().get(i));
                if (staticField) scheduler.storeStaticForScript(instance.scriptId(), idx, value);
                else scheduler.storeFieldForScript(instance.scriptId(), idx, value);
            }
        } catch (Throwable t) {
            if (host.logger() != null) host.logger().warn("Failed to load persistent state for " + instance.scriptId() + ": " + t.getMessage());
        }
    }

    private ScriptValue persistentValue(Object value, String typeName) {
        if (value == null) return io.velora.internal.vm.PrimitiveValue.nullValue();
        String baseType = typeName.endsWith("?") ? typeName.substring(0, typeName.length() - 1) : typeName;
        return switch (baseType) {
            case "Byte" -> io.velora.internal.vm.PrimitiveValue.of(((Number) value).byteValue());
            case "Int" -> io.velora.internal.vm.PrimitiveValue.of(((Number) value).intValue());
            case "Long" -> io.velora.internal.vm.PrimitiveValue.of(((Number) value).longValue());
            case "Float" -> io.velora.internal.vm.PrimitiveValue.of(((Number) value).floatValue());
            case "Double" -> io.velora.internal.vm.PrimitiveValue.of(((Number) value).doubleValue());
            case "Boolean" -> io.velora.internal.vm.PrimitiveValue.of((Boolean) value);
            case "Char" -> io.velora.internal.vm.PrimitiveValue.of(value instanceof Character c ? c : String.valueOf(value).charAt(0));
            case "String" -> new io.velora.internal.vm.StringValue((String) value);
            case "Duration" -> io.velora.internal.vm.PrimitiveValue.of(value instanceof java.time.Duration d ? d.toNanos() : ((Number) value).longValue());
            case "UUID" -> new io.velora.internal.vm.StringValue(value.toString());
            default -> throw new IllegalArgumentException("Unsupported persistent field type " + typeName);
        };
    }

    @Override
    public void loadEnabled() {
        for (ScriptInstance instance : repository.all()) {
            if (instance.compiledModule() != null && !instance.enabled()) {
                if (enabledScriptsStore != null && !enabledScriptsStore.isEnabled(instance.scriptId())) {
                    continue;
                }
                enable(instance.scriptId());
            }
        }
    }

    public void registerScript(ScriptInstance instance) {
        repository.register(instance);
    }


    public void failRuntime(String scriptId, String message) {
        ScriptInstance instance = repository.get(scriptId);
        if (instance == null) return;
        ScriptRuntime runtime = runtimes.remove(scriptId);
        if (runtime != null) runtime.stop();
        scheduler.cleanupScript(scriptId);
        RuntimeException error = new RuntimeException(message);
        instance.lastError(error);
        instance.statusMachine().transition(ScriptStatus.FAILED);
        if (enabledScriptsStore != null) enabledScriptsStore.disable(scriptId);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.RUNTIME_ERROR, scriptId, message));
    }

    public void fireServiceEvent(ScriptServiceEvents.Type type, String scriptId) {
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(type, scriptId));
    }

    public ScriptRepository repository() { return repository; }
    public ScriptRevisionManager revisionManager() { return revisionManager; }
    public ScriptServiceEventBus eventBus() { return eventBus; }

    @Override
    public void grantPermissions(String scriptId, io.velora.api.permission.PermissionSet set) {
        io.velora.api.permission.PermissionSet existing = grants.getOrDefault(scriptId, io.velora.api.permission.PermissionSet.empty());
        java.util.Set<io.velora.api.permission.ScriptPermission> combined = new java.util.HashSet<>();
        for (io.velora.api.permission.ScriptPermission p : existing.all()) combined.add(p);
        for (io.velora.api.permission.ScriptPermission p : set.all()) combined.add(p);
        grants.put(scriptId, io.velora.api.permission.PermissionSet.of(combined));
        persistGrants(scriptId);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.PERMISSIONS_CHANGED, scriptId));
    }

    @Override
    public void revokePermissions(String scriptId, io.velora.api.permission.PermissionSet set) {
        io.velora.api.permission.PermissionSet existing = grants.getOrDefault(scriptId, io.velora.api.permission.PermissionSet.empty());
        java.util.Set<io.velora.api.permission.ScriptPermission> remaining = new java.util.HashSet<>();
        for (io.velora.api.permission.ScriptPermission p : existing.all()) {
            if (!set.contains(p)) remaining.add(p);
        }
        grants.put(scriptId, io.velora.api.permission.PermissionSet.of(remaining));
        persistGrants(scriptId);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.PERMISSIONS_CHANGED, scriptId));
        // If script is enabled and no longer has required permissions, disable it
        ScriptInstance instance = repository.get(scriptId);
        if (instance != null && instance.enabled() && instance.compiledModule() != null) {
            io.velora.api.permission.PermissionSet required = instance.compiledModule().requiredPermissions();
            if (required != null && !required.isEmpty()) {
                io.velora.api.permission.PermissionSet granted = grants.getOrDefault(scriptId, io.velora.api.permission.PermissionSet.empty());
                if (!granted.containsAll(required)) {
                    disable(scriptId);
                }
            }
        }
    }

    private void persistGrants(String scriptId) {
        if (host == null || host.fileSystem() == null) return;
        try {
            io.velora.api.permission.PermissionSet granted = grants.getOrDefault(scriptId, io.velora.api.permission.PermissionSet.empty());
            StringBuilder sb = new StringBuilder();
            for (io.velora.api.permission.ScriptPermission p : granted.all()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p.id());
            }
            host.fileSystem().writeDataAtomic(scriptId, "grants.velora", sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            if (host != null && host.logger() != null) {
                host.logger().warn("Failed to persist grants for " + scriptId + ": " + t.getMessage());
            }
        }
    }

    private void loadGrants(String scriptId) {
        if (host == null || host.fileSystem() == null) return;
        if (grants.containsKey(scriptId)) return;
        try {
            byte[] data = host.fileSystem().readData(scriptId, "grants.velora");
            if (data == null || data.length == 0) return;
            String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            java.util.Set<io.velora.api.permission.ScriptPermission> loaded = new java.util.HashSet<>();
            for (String line : content.split("\n")) {
                String permId = line.trim();
                if (!permId.isEmpty()) {
                    io.velora.api.permission.ScriptPermission p = resolvePermissionById(permId);
                    if (p != null) loaded.add(p);
                }
            }
            if (!loaded.isEmpty()) {
                grants.put(scriptId, io.velora.api.permission.PermissionSet.of(loaded));
            }
        } catch (Throwable t) {
            if (host.logger() != null) host.logger().warn("Failed to load permission grants for " + scriptId + ": " + t.getMessage());
        }
    }

    private io.velora.api.permission.ScriptPermission resolvePermissionById(String permId) {
        return permissionRegistry != null ? permissionRegistry.find(permId) : null;
    }

    @Override
    public boolean hasPermissionGrant(String scriptId, io.velora.api.permission.PermissionSet required) {
        io.velora.api.permission.PermissionSet granted = grants.getOrDefault(scriptId, io.velora.api.permission.PermissionSet.empty());
        return granted.containsAll(required);
    }

    private final class ScriptHandleImpl implements ScriptHandle {
        private final ScriptInstance instance;

        ScriptHandleImpl(ScriptInstance instance) {
            this.instance = instance;
        }

        @Override public String id() { return instance.scriptId(); }
        @Override public ScriptDescriptor descriptor() { return instance.descriptor(); }
        @Override public ScriptStatus status() { return instance.status(); }
        @Override public boolean enabled() { return instance.enabled(); }
        @Override public ScriptOperationResult enable() { return DefaultScriptManager.this.enable(instance.scriptId()); }
        @Override public ScriptOperationResult disable() { return DefaultScriptManager.this.disable(instance.scriptId()); }
        @Override public ScriptOperationResult toggle() { return DefaultScriptManager.this.toggle(instance.scriptId()); }
        @Override public ScriptOperationResult reload() { return DefaultScriptManager.this.reload(instance.scriptId()); }
        @Override public SettingSchema settings() { return DefaultScriptManager.this.settings(instance.scriptId()); }
        @Override public Map<String, io.velora.api.setting.SettingValue> settingValues() { return DefaultScriptManager.this.settingValues(instance.scriptId()); }
        @Override public io.velora.api.debug.DebugSnapshot debug() { return debugService != null ? debugService.snapshot(instance.scriptId()) : io.velora.api.debug.DebugSnapshot.empty(instance.scriptId()); }
    }

    private static final class ScriptServiceEventsImpl implements ScriptServiceEvents {
        private final java.util.List<java.util.function.Consumer<ScriptServiceEvent>> listeners = new java.util.ArrayList<>();

        @Override
        public void subscribe(java.util.function.Consumer<ScriptServiceEvent> listener) {
            listeners.add(listener);
        }

        @Override
        public void unsubscribe(java.util.function.Consumer<ScriptServiceEvent> listener) {
            listeners.remove(listener);
        }

        void fire(ScriptServiceEvents.ScriptServiceEvent event) {
            for (var l : listeners) l.accept(event);
        }
    }
    private long nanoTime() { return host != null && host.clock() != null ? host.clock().nanoTime() : System.nanoTime(); }

}
