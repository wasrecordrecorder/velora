package io.velora.internal.script;

import io.velora.api.compiler.*;
import io.velora.api.script.*;
import io.velora.api.setting.SettingSchema;
import io.velora.api.setting.SettingDescriptor;
import io.velora.host.ScriptFileEntry;
import io.velora.host.VeloraHost;
import io.velora.host.SourceSnapshot;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.compiler.DefaultScriptCompiler;
import io.velora.internal.scheduler.FiberState;
import io.velora.internal.scheduler.ScriptFiber;
import io.velora.internal.scheduler.ScriptScheduler;
import io.velora.internal.setting.SettingStore;
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
    private final EnabledScriptsStore enabledScriptsStore;
    private final io.velora.api.debug.DebugService debugService;
    private final ScriptTemplateRegistry templateRegistry;

    public DefaultScriptManager(ScriptScheduler scheduler, ScriptCompiler compiler) {
        this(scheduler, compiler, null, null, null, null);
    }

    public DefaultScriptManager(ScriptScheduler scheduler, ScriptCompiler compiler, VeloraHost host) {
        this(scheduler, compiler, host, null, null, null);
    }

    public DefaultScriptManager(ScriptScheduler scheduler, ScriptCompiler compiler, VeloraHost host,
                                EnabledScriptsStore enabledScriptsStore, io.velora.api.debug.DebugService debugService,
                                ScriptTemplateRegistry templateRegistry) {
        this.scheduler = scheduler;
        this.compiler = compiler;
        this.host = host;
        this.enabledScriptsStore = enabledScriptsStore;
        this.debugService = debugService;
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
                sourceFiles, null, 0, 0, 0);
        ScriptInstance instance = ScriptInstanceFactory.create(scriptId, descriptor);
        repository.register(instance);
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
        persistEnabledState(scriptId, false);
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
        if (instance.compiledModule() == null) return ScriptOperationResult.failure(scriptId, "Script not compiled");

        loadPersistentFields(instance);
        ScriptRuntime runtime = new ScriptRuntime(instance, scheduler);
        runtimes.put(scriptId, runtime);
        instance.statusMachine().transition(ScriptStatus.ENABLING);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.STATUS_CHANGED, scriptId, ScriptStatus.ENABLING));

        CompiledModule module = instance.compiledModule();
        if (instance.settingStore() != null) scheduler.setSettingStore(scriptId, instance.settingStore());
        if (!instance.loadHookCompleted()) {
            if (module.lifecycleHooks().contains("ON_LOAD")) {
                Throwable failure = invokeLifecycle(instance, module, "ON_LOAD");
                if (failure != null) return failEnable(instance, runtime, "ON_LOAD", failure);
            }
            instance.loadHookCompleted(true);
        }
        if (module.lifecycleHooks().contains("ON_ENABLE")) {
            Throwable failure = invokeLifecycle(instance, module, "ON_ENABLE");
            if (failure != null) return failEnable(instance, runtime, "ON_ENABLE", failure);
        }

        if (!runtime.start()) return failEnable(instance, runtime, "ON_RUN", new IllegalStateException("Runtime resource limit rejected ON_RUN"));
        instance.statusMachine().transition(ScriptStatus.ENABLED);
        persistEnabledState(scriptId, true);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.ENABLED, scriptId, ScriptStatus.ENABLED));
        return ScriptOperationResult.success(scriptId, ScriptStatus.ENABLED);
    }

    private ScriptOperationResult failEnable(ScriptInstance instance, ScriptRuntime runtime, String hook, Throwable failure) {
        runtimes.remove(instance.scriptId());
        runtime.stop();
        String message = lifecycleMessage(hook, failure);
        instance.lastError(failure);
        instance.statusMachine().transition(ScriptStatus.FAILED);
        persistEnabledState(instance.scriptId(), false);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.RUNTIME_ERROR, instance.scriptId(), message));
        return ScriptOperationResult.failure(instance.scriptId(), message, failure);
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
        Throwable lifecycleFailure = null;
        if (runtime != null) {
            CompiledModule module = instance.compiledModule();
            if (module != null) lifecycleFailure = invokeLifecycle(instance, module, "ON_DISABLE");
            runtime.stop();
        }
        boolean saveOk = savePersistentFields(instance);
        instance.statusMachine().transition(ScriptStatus.DISABLING);
        instance.statusMachine().transition(ScriptStatus.DISABLED);
        persistEnabledState(scriptId, false);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.DISABLED, scriptId, ScriptStatus.DISABLED));
        if (lifecycleFailure != null) {
            String message = lifecycleMessage("ON_DISABLE", lifecycleFailure);
            recordLifecycleFailure(instance, message, lifecycleFailure);
            return ScriptOperationResult.failure(scriptId, message, lifecycleFailure);
        }
        if (!saveOk) return ScriptOperationResult.failure(scriptId, "State persistence failed");
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
        CompileAttempt attempt = compileModule(instance);
        instance.diagnostics(attempt.diagnostics());
        if (attempt.module() == null) {
            updateDiagnosticCounts(instance, attempt.diagnostics(), instance.status());
            return ScriptOperationResult.failure(scriptId, attempt.diagnostics().isEmpty() ? "Compilation failed" : attempt.diagnostics().toString());
        }
        CompiledModule candidate = attempt.module();

        boolean wasEnabled = instance.enabled();
        CompiledModule oldModule = instance.compiledModule();
        SettingStore oldSettings = instance.settingStore();
        ScriptDescriptor oldDescriptor = instance.descriptor();
        long oldRevision = instance.revision();
        boolean oldLoadHookCompleted = instance.loadHookCompleted();
        boolean oldUnloadCompleted = false;

        if (wasEnabled) {
            ScriptOperationResult disabled = disable(scriptId);
            if (!disabled.success()) {
                if (!instance.enabled()) enable(scriptId);
                return ScriptOperationResult.failure(scriptId, "Failed to disable current script before reload: " + disabled.message(), disabled.cause());
            }
        }
        if (oldModule != null && oldLoadHookCompleted) {
            Throwable failure = invokeLifecycle(instance, oldModule, "ON_UNLOAD");
            if (failure != null) {
                String message = lifecycleMessage("ON_UNLOAD", failure);
                recordLifecycleFailure(instance, message, failure);
                if (wasEnabled) enable(scriptId);
                return ScriptOperationResult.failure(scriptId, message, failure);
            }
            oldUnloadCompleted = true;
        }

        instance.statusMachine().transition(ScriptStatus.RELOADING);
        instance.compiledModule(candidate);
        instance.loadHookCompleted(false);
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
                instance.loadHookCompleted(oldUnloadCompleted ? false : oldLoadHookCompleted);
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

    private Throwable invokeLifecycle(ScriptInstance instance, CompiledModule module, String hook) {
        int functionIndex = findFunctionIndex(module, hook);
        if (functionIndex < 0) return null;
        if (instance.settingStore() != null) scheduler.setSettingStore(instance.scriptId(), instance.settingStore());
        ScriptFiber fiber = scheduler.spawnFiberAndAwait(instance.scriptId(), functionIndex, new ScriptValue[0], schedulerModules(), schedulerSettings());
        if (fiber == null) return new IllegalStateException("Runtime resource limit rejected " + hook);
        if (fiber.state() == FiberState.COMPLETED) return null;
        return fiber.error() != null ? fiber.error() : new IllegalStateException(hook + " ended with " + fiber.state());
    }

    private String lifecycleMessage(String hook, Throwable failure) {
        String detail = failure.getMessage();
        return hook + " lifecycle failed" + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    private void recordLifecycleFailure(ScriptInstance instance, String message, Throwable failure) {
        instance.lastError(failure);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.RUNTIME_ERROR, instance.scriptId(), message));
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
        return new SettingStore(module.settings());
    }

    private ScriptDescriptor descriptorForModule(ScriptDescriptor current, CompiledModule module, ScriptRevision revision, long reloadTime) {
        return new ScriptDescriptor(current.id(), module.scriptName() != null ? module.scriptName() : current.name(),
                module.version() != null ? module.version() : current.version(),
                module.author() != null ? module.author() : current.author(),
                module.description() != null ? module.description() : current.description(),
                ScriptStatus.LOADED, false, current.sourceFiles(), revision,
                current.errorCount(), current.warningCount(), reloadTime);
    }

    @Override
    public ScriptOperationResult unload(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        if (instance == null) return ScriptOperationResult.failure(scriptId, "Script not found");
        ScriptRuntime runtime = runtimes.remove(scriptId);
        Throwable failure = null;
        String failedHook = null;
        CompiledModule module = instance.compiledModule();
        if (runtime != null) {
            if (module != null) {
                failure = invokeLifecycle(instance, module, "ON_DISABLE");
                if (failure != null) failedHook = "ON_DISABLE";
            }
            runtime.stop();
        }
        boolean saveOk = savePersistentFields(instance);
        if (module != null && instance.loadHookCompleted()) {
            Throwable unloadFailure = invokeLifecycle(instance, module, "ON_UNLOAD");
            if (failure == null && unloadFailure != null) {
                failure = unloadFailure;
                failedHook = "ON_UNLOAD";
            }
        }
        scheduler.cleanupScript(scriptId);
        instance.statusMachine().transition(ScriptStatus.UNLOADED);
        repository.remove(scriptId);
        runtimes.remove(scriptId);
        revisionManager.clear(scriptId);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.STATUS_CHANGED, scriptId, ScriptStatus.UNLOADED));
        if (failure != null) {
            String message = lifecycleMessage(failedHook, failure);
            recordLifecycleFailure(instance, message, failure);
            return ScriptOperationResult.failure(scriptId, message, failure);
        }
        if (!saveOk) return ScriptOperationResult.failure(scriptId, "State persistence failed");
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
    public List<Diagnostic> diagnostics(String scriptId) {
        ScriptInstance instance = repository.get(scriptId);
        return instance != null ? instance.diagnostics() : List.of();
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
        loadEnabledState();
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
                    ScriptStatus.DISCOVERED, false, sourceFiles,
                    null, 0, 0, 0);
            ScriptInstance instance = ScriptInstanceFactory.create(scriptId, descriptor);
            repository.register(instance);
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
                current.status(), current.enabled(), paths, current.activeRevision(),
                current.errorCount(), current.warningCount(), current.lastReloadTimeNanos()));
    }

    private CompileAttempt compileModule(ScriptInstance instance) {
        if (host == null || host.fileSystem() == null || !(compiler instanceof DefaultScriptCompiler defaultCompiler)) return new CompileAttempt(null, List.of());
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.COMPILE_STARTED, instance.scriptId()));
        List<SourceFile> sources = new ArrayList<>();
        for (String path : instance.descriptor().sourceFiles()) {
            SourceSnapshot snapshot = host.fileSystem().readSource(instance.scriptId(), path);
            if (snapshot != null) sources.add(new SourceFile(path, snapshot.content(), snapshot.contentHash()));
        }
        if (sources.isEmpty()) {
            serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.COMPILE_FINISHED, instance.scriptId()));
            return new CompileAttempt(null, List.of(Diagnostic.error(DiagnosticCode.COMPILER_BAD_SOURCE, "No source files", SourceRange.of("main.vls", 0, 0))));
        }
        CompileRequest full = CompileRequest.builder(instance.scriptId()).sources(sources).mode(CompileMode.FULL).build();
        CompileResult result = defaultCompiler.compile(full);
        CompiledModule module = null;
        if (result.success()) {
            CompileRequest cached = CompileRequest.builder(instance.scriptId()).sources(sources).mode(CompileMode.INCREMENTAL).build();
            module = defaultCompiler.compileToModule(cached);
        }
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.COMPILE_FINISHED, instance.scriptId()));
        return new CompileAttempt(module, result.diagnostics());
    }

    private void compileInstance(ScriptInstance instance) {
        CompileAttempt attempt = compileModule(instance);
        instance.diagnostics(attempt.diagnostics());
        CompiledModule module = attempt.module();
        if (module == null) {
            instance.compiledModule(null);
            instance.loadHookCompleted(false);
            instance.statusMachine().transition(ScriptStatus.FAILED);
            updateDiagnosticCounts(instance, attempt.diagnostics(), ScriptStatus.FAILED);
            return;
        }
        instance.compiledModule(module);
        instance.loadHookCompleted(false);
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
        int errors = diagnosticCount(attempt.diagnostics(), DiagnosticSeverity.ERROR);
        int warnings = diagnosticCount(attempt.diagnostics(), DiagnosticSeverity.WARNING);
        instance.descriptor(new ScriptDescriptor(
                d.id(), name, version, author, description,
                instance.status(), instance.enabled(), d.sourceFiles(), rev,
                errors, warnings, d.lastReloadTimeNanos()));
    }

    private void updateDiagnosticCounts(ScriptInstance instance, List<Diagnostic> diagnostics, ScriptStatus status) {
        ScriptDescriptor d = instance.descriptor();
        instance.descriptor(new ScriptDescriptor(d.id(), d.name(), d.version(), d.author(), d.description(), status,
                instance.enabled(), d.sourceFiles(), d.activeRevision(),
                diagnosticCount(diagnostics, DiagnosticSeverity.ERROR), diagnosticCount(diagnostics, DiagnosticSeverity.WARNING), d.lastReloadTimeNanos()));
    }

    private int diagnosticCount(List<Diagnostic> diagnostics, DiagnosticSeverity severity) {
        int count = 0;
        for (Diagnostic diagnostic : diagnostics) if (diagnostic.severity() == severity) count++;
        return count;
    }

    private record CompileAttempt(CompiledModule module, List<Diagnostic> diagnostics) {
        private CompileAttempt {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private void loadSettingsFromDisk(ScriptInstance instance) {
        if (host == null || host.fileSystem() == null || instance.settingStore() == null) return;
        try {
            var fs = host.fileSystem();
            byte[] data = fs.readData(instance.scriptId(), "settings.velora");
            if (data != null && data.length > 0) {
                String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                var loaded = io.velora.internal.persistence.SettingsFileCodec.decode(content, instance.settingStore().descriptors());
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
        persistEnabledState(scriptId, false);
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(ScriptServiceEvents.Type.RUNTIME_ERROR, scriptId, message));
    }

    public void fireServiceEvent(ScriptServiceEvents.Type type, String scriptId) {
        serviceEvents.fire(ScriptServiceEvents.ScriptServiceEvent.of(type, scriptId));
    }

    public ScriptRepository repository() { return repository; }
    public ScriptRevisionManager revisionManager() { return revisionManager; }
    public ScriptServiceEventBus eventBus() { return eventBus; }

    private final class ScriptHandleImpl implements ScriptHandle {
        private final ScriptInstance instance;

        ScriptHandleImpl(ScriptInstance instance) {
            this.instance = instance;
        }

        @Override public String id() { return instance.scriptId(); }
        @Override public ScriptDescriptor descriptor() {
            ScriptDescriptor descriptor = instance.descriptor();
            return new ScriptDescriptor(descriptor.id(), descriptor.name(), descriptor.version(), descriptor.author(), descriptor.description(),
                    instance.status(), instance.enabled(), descriptor.sourceFiles(), descriptor.activeRevision(), descriptor.errorCount(), descriptor.warningCount(), descriptor.lastReloadTimeNanos());
        }
        @Override public ScriptStatus status() { return instance.status(); }
        @Override public boolean enabled() { return instance.enabled(); }
        @Override public ScriptOperationResult enable() { return DefaultScriptManager.this.enable(instance.scriptId()); }
        @Override public ScriptOperationResult disable() { return DefaultScriptManager.this.disable(instance.scriptId()); }
        @Override public ScriptOperationResult toggle() { return DefaultScriptManager.this.toggle(instance.scriptId()); }
        @Override public ScriptOperationResult reload() { return DefaultScriptManager.this.reload(instance.scriptId()); }
        @Override public SettingSchema settings() { return DefaultScriptManager.this.settings(instance.scriptId()); }
        @Override public Map<String, io.velora.api.setting.SettingValue> settingValues() { return DefaultScriptManager.this.settingValues(instance.scriptId()); }
        @Override public List<Diagnostic> diagnostics() { return DefaultScriptManager.this.diagnostics(instance.scriptId()); }
        @Override public io.velora.api.debug.DebugSnapshot debug() { return debugService != null ? debugService.snapshot(instance.scriptId()) : io.velora.api.debug.DebugSnapshot.empty(instance.scriptId()); }
    }

    private final class ScriptServiceEventsImpl implements ScriptServiceEvents {
        private final java.util.List<java.util.function.Consumer<ScriptServiceEvent>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void subscribe(java.util.function.Consumer<ScriptServiceEvent> listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
        }

        @Override
        public void unsubscribe(java.util.function.Consumer<ScriptServiceEvent> listener) {
            listeners.remove(listener);
        }

        void fire(ScriptServiceEvents.ScriptServiceEvent event) {
            for (var listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException error) {
                    if (host != null && host.logger() != null) host.logger().error("Script service listener failed", error);
                }
            }
        }
    }
    private void persistEnabledState(String scriptId, boolean enabled) {
        if (enabledScriptsStore == null) return;
        boolean persisted = enabled ? enabledScriptsStore.enable(scriptId) : enabledScriptsStore.disable(scriptId);
        if (!persisted && host != null && host.logger() != null) {
            host.logger().warn("Failed to persist auto-enable state for " + scriptId);
        }
    }

    private void loadEnabledState() {
        if (enabledScriptsStore != null && !enabledScriptsStore.load() && host != null && host.logger() != null) {
            host.logger().warn("Failed to load auto-enable state");
        }
    }

    private long nanoTime() { return host != null && host.clock() != null ? host.clock().nanoTime() : System.nanoTime(); }

}
