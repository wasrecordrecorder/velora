package io.velora.internal.runtime;

import io.velora.api.compiler.*;
import io.velora.api.script.*;
import io.velora.api.setting.SettingSchema;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.type.VeloraTypes;
import io.velora.host.ScriptFileEntry;
import io.velora.host.VeloraHost;
import io.velora.host.SourceSnapshot;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.scheduler.ScriptScheduler;
import io.velora.internal.setting.SettingStore;
import io.velora.internal.security.PermissionController;
import io.velora.internal.security.HandleValidator;
import io.velora.internal.persistence.EnabledScriptsStore;
import io.velora.internal.vm.ScriptValue;

import java.util.*;
import java.util.function.Consumer;

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
    private final HandleValidator handleValidator;
    private final Consumer<String> eventCounterResetCallback;

    public DefaultScriptManager(ScriptScheduler scheduler, ScriptCompiler compiler) {
        this(scheduler, compiler, null, null, null, null, null);
    }

    public DefaultScriptManager(ScriptScheduler scheduler, ScriptCompiler compiler, VeloraHost host) {
        this(scheduler, compiler, host, null, null, null, null);
    }

    public DefaultScriptManager(ScriptScheduler scheduler, ScriptCompiler compiler, VeloraHost host,
                                 PermissionController permissionController, EnabledScriptsStore enabledScriptsStore,
                                 HandleValidator handleValidator) {
        this(scheduler, compiler, host, permissionController, enabledScriptsStore, handleValidator, null);
    }

    public DefaultScriptManager(ScriptScheduler scheduler, ScriptCompiler compiler, VeloraHost host,
                                 PermissionController permissionController, EnabledScriptsStore enabledScriptsStore,
                                 HandleValidator handleValidator, Consumer<String> eventCounterResetCallback) {
        this.scheduler = scheduler;
        this.compiler = compiler;
        this.host = host;
        this.permissionController = permissionController;
        this.enabledScriptsStore = enabledScriptsStore;
        this.handleValidator = handleValidator;
        this.eventCounterResetCallback = eventCounterResetCallback;
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
        Map<String, CompiledModule> mods = Map.of(scriptId, module);
        Map<String, List<io.velora.api.setting.SettingDescriptor>> sets = Map.of(scriptId, module.settings());
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
        
        instance.statusMachine().transition(ScriptStatus.ENABLED);
        runtime.start();
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
                    Map<String, CompiledModule> mods = Map.of(scriptId, module);
                    Map<String, List<io.velora.api.setting.SettingDescriptor>> sets = Map.of(scriptId, module.settings());
                    scheduler.spawnFiberAndAwait(instance.scriptId(), fnIdx, new ScriptValue[0], mods, sets);
                }
            }
            runtime.stop();
        }
        // Save persistent fields before disabling
        boolean saveOk = savePersistentFields(instance);
        instance.statusMachine().transition(ScriptStatus.DISABLING);
        instance.statusMachine().transition(ScriptStatus.DISABLED);
        if (enabledScriptsStore != null) enabledScriptsStore.disable(scriptId);
        if (eventCounterResetCallback != null) eventCounterResetCallback.accept(scriptId);
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
        boolean wasEnabled = instance.enabled();
        if (wasEnabled) {
            // Call ON_UNLOAD on the old module before disabling
            CompiledModule oldModule = instance.compiledModule();
            if (oldModule != null) {
                int fnIdx = findFunctionIndex(oldModule, "ON_UNLOAD");
                if (fnIdx >= 0) {
                    Map<String, CompiledModule> mods = Map.of(scriptId, oldModule);
                    Map<String, List<io.velora.api.setting.SettingDescriptor>> sets = Map.of(scriptId, oldModule.settings());
                    if (instance.settingStore() != null) {
                        scheduler.setSettingStore(scriptId, instance.settingStore());
                    }
                    scheduler.spawnFiberAndAwait(instance.scriptId(), fnIdx, new ScriptValue[0], mods, sets);
                }
            }
            disable(scriptId);
        }
        
        CompiledModule oldModule = instance.compiledModule();
        long oldRevision = instance.revision();
        ScriptRevision oldActiveRev = instance.descriptor().activeRevision();
        
        instance.statusMachine().transition(ScriptStatus.RELOADING);
        compileInstance(instance);
        
        if (instance.compiledModule() == null || instance.compiledModule() == oldModule) {
            instance.statusMachine().transition(ScriptStatus.LOADED);
            if (wasEnabled && !instance.enabled()) enable(scriptId);
            return ScriptOperationResult.failure(scriptId, "Compilation failed");
        }
        
        instance.revision(oldRevision + 1);
        ScriptDescriptor d = instance.descriptor();
        ScriptRevision rev = new ScriptRevision(scriptId, oldRevision + 1, instance.compiledModule().sourceHash(), System.nanoTime());
        CompiledModule newModule = instance.compiledModule();
        String name = newModule.scriptName() != null ? newModule.scriptName() : d.name();
        String version = newModule.version() != null ? newModule.version() : d.version();
        String author = newModule.author() != null ? newModule.author() : d.author();
        String description = newModule.description() != null ? newModule.description() : d.description();
        instance.descriptor(new ScriptDescriptor(
            d.id(), name, version, author, description,
            instance.status(), instance.enabled(),
            d.sourceFiles(), d.permissions(), rev,
            d.errorCount(), d.warningCount(), System.nanoTime()
        ));
        
        instance.statusMachine().transition(ScriptStatus.LOADED);
        if (wasEnabled && !instance.enabled()) enable(scriptId);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.RELOADED, scriptId));
        return ScriptOperationResult.success(scriptId, instance.status());
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
                    Map<String, CompiledModule> mods = Map.of(scriptId, module);
                    Map<String, List<io.velora.api.setting.SettingDescriptor>> sets = Map.of(scriptId, module.settings());
                    scheduler.spawnFiberAndAwait(instance.scriptId(), fnIdx, new ScriptValue[0], mods, sets);
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
                Map<String, CompiledModule> mods = Map.of(scriptId, module);
                Map<String, List<io.velora.api.setting.SettingDescriptor>> sets = Map.of(scriptId, module.settings());
                scheduler.spawnFiberAndAwait(instance.scriptId(), fnIdx, new ScriptValue[0], mods, sets);
            }
        }
        instance.statusMachine().transition(ScriptStatus.UNLOADED);
        repository.remove(scriptId);
        runtimes.remove(scriptId);
        revisionManager.clear(scriptId);
        if (eventCounterResetCallback != null) eventCounterResetCallback.accept(scriptId);
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
    public ScriptTransaction beginTransaction(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        SettingSchema schema = settings(scriptId);
        io.velora.internal.setting.SettingStore store = instance != null ? instance.settingStore() : null;
        return new ScriptTransactionImpl(scriptId, compiler, host, schema, serviceEvents::fire, store);
    }

    @Override
    public ScriptServiceEvents events() {
        return serviceEvents;
    }

    @Override
    public void discover() {
        if (enabledScriptsStore != null) enabledScriptsStore.load();
        if (host == null || host.fileSystem() == null) return;
        for (ScriptFileEntry entry : host.fileSystem().listScripts()) {
            if (repository.get(entry.scriptId()) != null) continue;
            ScriptDescriptor desc = new ScriptDescriptor(
                    entry.scriptId(), entry.scriptId(), "1.0.0",
                    null, null, ScriptStatus.DISCOVERED, false,
                    List.of(entry.relativePath()),
                    io.velora.api.permission.PermissionSet.empty(),
                    null, 0, 0, 0
            );
            ScriptInstance instance = ScriptInstanceFactory.create(entry.scriptId(), desc);
            repository.register(instance);
            loadGrants(entry.scriptId());
            serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.DISCOVERED, entry.scriptId()));
            compileInstance(instance);
        }
    }

    private void compileInstance(ScriptInstance instance) {
        if (host == null || host.fileSystem() == null) return;
        if (!(compiler instanceof DefaultScriptCompiler)) return;
        DefaultScriptCompiler defaultCompiler = (DefaultScriptCompiler) compiler;
        var fs = host.fileSystem();
        List<io.velora.api.compiler.SourceFile> sources = new ArrayList<>();
        for (String path : instance.descriptor().sourceFiles()) {
            SourceSnapshot snapshot = fs.readSource(instance.scriptId(), path);
            if (snapshot != null) {
                sources.add(new io.velora.api.compiler.SourceFile(path, snapshot.content(), snapshot.contentHash()));
            }
        }
        if (sources.isEmpty()) return;
        CompileRequest request = CompileRequest.builder(instance.scriptId())
                .sources(sources)
                .mode(CompileMode.FULL)
                .build();
        CompiledModule module = defaultCompiler.compileToModule(request);
        if (module != null) {
            instance.compiledModule(module);
            List<SettingDescriptor> settingsList = new ArrayList<>(module.settings());
            if (settingsList.isEmpty()) {
                settingsList.add(new SettingDescriptor("enabled", "Enabled", VeloraTypes.BOOLEAN, Boolean.TRUE,
                    null, "Whether the script is enabled", null, 0, false, false, false,
                    null, List.of(), 0));
            }
            instance.settingStore(new SettingStore(settingsList));
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
            serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.COMPILE_FINISHED, instance.scriptId()));
            if (module.lifecycleHooks().contains("ON_LOAD")
                && !module.lifecycleHooks().contains("ON_ENABLE")
                && instance.status() != ScriptStatus.RELOADING) {
                enable(instance.scriptId());
            }
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
                instance.settingStore().applySnapshot(loaded);
            }
        } catch (Throwable t) {
            // best-effort load
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
            if (value != null && !value.isNull()) {
                state.put(pid, value.boxed());
            }
        }
        if (!state.isEmpty()) {
            try {
                String encoded = io.velora.internal.persistence.StateFileCodec.encode(state);
                host.fileSystem().writeDataAtomic(instance.scriptId(), "state.bin", encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable t) {
                if (host != null && host.logger() != null) {
                    host.logger().error("Failed to persist state for " + instance.scriptId() + ": " + t.getMessage(), t);
                }
                return false;
            }
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
                Object value = state.get(pid);
                if (value != null) {
                    ScriptValue sv = javaToScriptValue(value);
                    if (staticField) {
                        scheduler.storeStaticForScript(instance.scriptId(), idx, sv);
                    } else {
                        scheduler.storeFieldForScript(instance.scriptId(), idx, sv);
                    }
                }
            }
        } catch (Throwable t) {
            // best-effort load
        }
    }

    private ScriptValue javaToScriptValue(Object value) {
        if (value instanceof Integer i) return io.velora.internal.vm.PrimitiveValue.of(i);
        if (value instanceof Long l) return io.velora.internal.vm.PrimitiveValue.of(l);
        if (value instanceof Double d) return io.velora.internal.vm.PrimitiveValue.of(d);
        if (value instanceof Float f) return io.velora.internal.vm.PrimitiveValue.of(f);
        if (value instanceof Boolean b) return io.velora.internal.vm.PrimitiveValue.of(b);
        if (value instanceof String s) return new io.velora.internal.vm.StringValue(s);
        return io.velora.internal.vm.PrimitiveValue.nullValue();
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
            // best-effort load
        }
    }

    private io.velora.api.permission.ScriptPermission resolvePermissionById(String permId) {
        // Try to find in permission registry via engine — but we don't have direct access here
        // Permission IDs in grants are stored as the canonical form (e.g. "WORLD_READ" or "client.world.read")
        // We need to match against registered permissions
        // Since we don't have PermissionRegistry here, we store the permission as-is
        return io.velora.api.permission.ScriptPermission.of(permId, permId, "");
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
        @Override public io.velora.api.debug.DebugSnapshot debug() { return null; }
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
}
