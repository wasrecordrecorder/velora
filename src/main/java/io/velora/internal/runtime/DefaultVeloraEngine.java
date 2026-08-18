package io.velora.internal.runtime;

import io.velora.api.*;
import io.velora.api.category.ApiCategory;
import io.velora.api.category.CategoryRegistry;
import io.velora.api.compiler.ScriptCompiler;
import io.velora.api.debug.DebugService;
import io.velora.api.debug.ScriptLogEntry;
import io.velora.api.event.EventRegistry;
import io.velora.api.event.EventDescriptor;
import io.velora.api.event.EventConcurrency;
import io.velora.api.event.EventOverflowPolicy;
import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.language.LanguageService;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.registry.*;
import io.velora.api.script.ScriptManager;
import io.velora.api.setting.SettingKind;
import io.velora.api.type.VeloraTypes;
import io.velora.host.VeloraHost;
import io.velora.internal.debug.*;
import io.velora.internal.compiler.DefaultScriptCompiler;
import io.velora.internal.registry.*;
import io.velora.internal.script.*;
import io.velora.internal.event.DefaultEventRegistry;
import io.velora.internal.language.DefaultLanguageService;
import io.velora.internal.interop.DefaultJavaImportRegistry;
import io.velora.internal.scheduler.ScriptScheduler;
import io.velora.internal.setting.DefaultSettingRegistry;
import io.velora.internal.security.*;
import io.velora.internal.persistence.EnabledScriptsStore;
import io.velora.internal.vm.ScriptValue;
import io.velora.internal.vm.VirtualMachine;

import java.util.*;

public final class DefaultVeloraEngine implements VeloraEngine {

    private final VeloraEngineBuilder builder;
    private final DefaultTypeRegistry typeRegistry;
    private final DefaultSettingRegistry settingRegistry;
    private final DefaultConstantRegistry constantRegistry;
    private final DefaultApiRegistry apiRegistry;
    private final DefaultJavaImportRegistry javaImportRegistry;
    private final DefaultExtensionRegistry extensionRegistry;
    private final DefaultEventRegistry eventRegistry;
    private final DefaultScriptTemplateRegistry templateRegistry;
    private final DefaultCategoryRegistry categoryRegistry;
    private DefaultScriptCompiler compiler;
    private DefaultScriptManager scriptManager;
    private ScriptScheduler scheduler;
    private LanguageService languageService;
    private DebugService debugService;
    private final Profiler profiler = new Profiler();
    private final RuntimeErrorStore errorStore = new RuntimeErrorStore(100);
    private final ScriptLogStore logStore = new ScriptLogStore(1000);
    private final EnabledScriptsStore enabledScriptsStore;
    private final LogRateLimiter logRateLimiter = new LogRateLimiter(100);
    private final Map<EventHandlerKey, Long> runningEventHandlers = new HashMap<>();
    private final Map<EventHandlerKey, Deque<ScriptValue[]>> pendingEventHandlers = new HashMap<>();
    private final Map<String, Integer> pendingEventCountsByScript = new HashMap<>();
    private VeloraState state = VeloraState.CREATED;

    private DefaultVeloraEngine(VeloraEngineBuilder builder) {
        this.builder = builder;
        this.enabledScriptsStore = new EnabledScriptsStore(builder.host().fileSystem());
        this.typeRegistry = new DefaultTypeRegistry();
        this.settingRegistry = new DefaultSettingRegistry();
        this.constantRegistry = new DefaultConstantRegistry();
        this.apiRegistry = new DefaultApiRegistry(typeRegistry);
        this.javaImportRegistry = new DefaultJavaImportRegistry(apiRegistry, typeRegistry);
        registerBuiltInApi();
        registerBuiltInSettings();
        this.extensionRegistry = new DefaultExtensionRegistry();
        this.eventRegistry = new DefaultEventRegistry(builder.host());
        this.templateRegistry = new DefaultScriptTemplateRegistry();
        this.categoryRegistry = new DefaultCategoryRegistry();
        // Register built-in categories
        categoryRegistry.register(new ApiCategory("core", "Core", "Core engine functionality"));
        categoryRegistry.register(new ApiCategory("console", "Console", "Script console output"));
        categoryRegistry.register(new ApiCategory("log", "Logging", "Logging utilities"));
        categoryRegistry.register(new ApiCategory("settings", "Settings", "Script settings and configuration"));
        this.state = VeloraState.CONFIGURING;
    }

    public static DefaultVeloraEngine create(VeloraEngineBuilder builder) {
        return new DefaultVeloraEngine(builder);
    }

    private void registerBuiltInApi() {
        var hostLogger = builder.host().logger();
        apiRegistry.namespace("console", ns -> {
            ns.function("print", VeloraTypes.UNIT, p -> p.required("message", VeloraTypes.STRING), ctx -> { writeLog(ctx, ScriptLogEntry.Level.INFO, String.valueOf(ctx.argument(0)), hostLogger); return null; }).description("Prints a message to the host console").categoryId("console");
            ns.function("info", VeloraTypes.UNIT, p -> p.required("message", VeloraTypes.STRING), ctx -> { writeLog(ctx, ScriptLogEntry.Level.INFO, String.valueOf(ctx.argument(0)), hostLogger); return null; }).description("Prints an informational message").categoryId("console");
            ns.function("warn", VeloraTypes.UNIT, p -> p.required("message", VeloraTypes.STRING), ctx -> { writeLog(ctx, ScriptLogEntry.Level.WARN, String.valueOf(ctx.argument(0)), hostLogger); return null; }).description("Prints a warning message").categoryId("console");
            ns.function("error", VeloraTypes.UNIT, p -> p.required("message", VeloraTypes.STRING), ctx -> { writeLog(ctx, ScriptLogEntry.Level.ERROR, String.valueOf(ctx.argument(0)), hostLogger); return null; }).description("Prints an error message").categoryId("console");
            ns.function("debug", VeloraTypes.UNIT, p -> p.required("message", VeloraTypes.STRING), ctx -> { writeLog(ctx, ScriptLogEntry.Level.DEBUG, String.valueOf(ctx.argument(0)), hostLogger); return null; }).description("Prints a debug message").categoryId("console");
        });
        apiRegistry.namespace("log", ns -> {
            ns.function("info", VeloraTypes.UNIT, p -> p.required("message", VeloraTypes.STRING), ctx -> { writeLog(ctx, ScriptLogEntry.Level.INFO, String.valueOf(ctx.argument(0)), hostLogger); return null; }).description("Logs an informational message").categoryId("log");
            ns.function("warn", VeloraTypes.UNIT, p -> p.required("message", VeloraTypes.STRING), ctx -> { writeLog(ctx, ScriptLogEntry.Level.WARN, String.valueOf(ctx.argument(0)), hostLogger); return null; }).description("Logs a warning message").categoryId("log");
            ns.function("error", VeloraTypes.UNIT, p -> p.required("message", VeloraTypes.STRING), ctx -> { writeLog(ctx, ScriptLogEntry.Level.ERROR, String.valueOf(ctx.argument(0)), hostLogger); return null; }).description("Logs an error message").categoryId("log");
            ns.function("debug", VeloraTypes.UNIT, p -> p.required("message", VeloraTypes.STRING), ctx -> { writeLog(ctx, ScriptLogEntry.Level.DEBUG, String.valueOf(ctx.argument(0)), hostLogger); return null; }).description("Logs a debug message").categoryId("log");
        });
    }

    private void writeLog(io.velora.api.function.FunctionContext ctx, ScriptLogEntry.Level level, String message, io.velora.host.VeloraLogger hostLogger) {
        if (!logRateLimiter.canLog(ctx.scriptId())) return;
        logRateLimiter.recordLog(ctx.scriptId());
        logStore.log(ctx.scriptId(), new ScriptLogEntry(ctx.scriptId(), ctx.fiberId(), level, message, builder.host().clock().nanoTime()));
        if (scriptManager != null) scriptManager.fireServiceEvent(io.velora.api.script.ScriptServiceEvents.Type.LOG_ADDED, ctx.scriptId());
        switch (level) {
            case DEBUG -> hostLogger.debug(message);
            case INFO -> hostLogger.info(message);
            case WARN -> hostLogger.warn(message);
            case ERROR -> hostLogger.error(message, null);
        }
    }

    private void registerBuiltInSettings() {
        settingRegistry.register(
            SettingKind.named("Number")
                .identifierParameter()
                .positional("name", SettingKind.Parameter.ParameterRole.DISPLAY_NAME, VeloraTypes.STRING, true)
                .positional("min", SettingKind.Parameter.ParameterRole.MIN, VeloraTypes.DOUBLE, true)
                .positional("max", SettingKind.Parameter.ParameterRole.MAX, VeloraTypes.DOUBLE, true)
                .positional("step", SettingKind.Parameter.ParameterRole.STEP, VeloraTypes.DOUBLE, true)
                .positional("defaultValue", SettingKind.Parameter.ParameterRole.DEFAULT_VALUE, VeloraTypes.DOUBLE, true)
                .positional("editor", SettingKind.Parameter.ParameterRole.NAMED, VeloraTypes.STRING, false)
                .resultTypeResolver(declaration -> {
                    List<Object> args = declaration.positionalArguments();
                    if (args.size() > 4) {
                        Object value = args.get(4);
                        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                            return VeloraTypes.INT;
                        }
                    }
                    return VeloraTypes.DOUBLE;
                })
                .editor("number")
                .build()
        );
        settingRegistry.register(
            SettingKind.named("String")
                .identifierParameter()
                .positional("name", SettingKind.Parameter.ParameterRole.DISPLAY_NAME, VeloraTypes.STRING, true)
                .positional("minLength", SettingKind.Parameter.ParameterRole.MIN, VeloraTypes.INT, true)
                .positional("maxLength", SettingKind.Parameter.ParameterRole.MAX, VeloraTypes.INT, true)
                .positional("defaultValue", SettingKind.Parameter.ParameterRole.DEFAULT_VALUE, VeloraTypes.STRING, true)
                .positional("editor", SettingKind.Parameter.ParameterRole.NAMED, VeloraTypes.STRING, false)
                .resultType(VeloraTypes.STRING)
                .editor("string")
                .build()
        );
        settingRegistry.register(
            SettingKind.named("Boolean")
                .identifierParameter()
                .positional("name", SettingKind.Parameter.ParameterRole.DISPLAY_NAME, VeloraTypes.STRING, true)
                .positional("defaultValue", SettingKind.Parameter.ParameterRole.DEFAULT_VALUE, VeloraTypes.BOOLEAN, true)
                .resultType(VeloraTypes.BOOLEAN)
                .editor("boolean")
                .build()
        );
    }

    @Override
    public VeloraHost host() { return builder.host(); }

    @Override
    public VeloraLimits limits() { return builder.limits(); }

    @Override
    public VeloraState state() { return state; }

    @Override
    public ApiRegistry api() { return apiRegistry; }

    @Override
    public EventRegistry events() { return eventRegistry; }

    @Override
    public TypeRegistry types() { return typeRegistry; }

    @Override
    public SettingRegistry settings() { return settingRegistry; }

    @Override
    public ConstantRegistry constants() { return constantRegistry; }

    @Override
    public JavaImportRegistry javaImports() { return javaImportRegistry; }

    @Override
    public VeloraExtensionRegistry extensions() { return extensionRegistry; }

    @Override
    public CategoryRegistry categories() { return categoryRegistry; }

    @Override
    public ScriptCompiler compiler() {
        ensureOpen();
        if (compiler == null) {
            compiler = new DefaultScriptCompiler(typeRegistry, settingRegistry, apiRegistry,
                    constantRegistry, eventRegistry, javaImportRegistry);
        }
        return compiler;
    }

    @Override
    public ScriptManager scripts() {
        ensureOpen();
        if (scriptManager == null) {
            if (scheduler == null) {
                scheduler = new ScriptScheduler(builder.limits(), apiRegistry, errorStore, builder.host().workers(), constantRegistry, typeRegistry, builder.host().clock()::nanoTime, builder.host().mainThread()::isMainThread);
            }
            if (compiler == null) {
                compiler = new DefaultScriptCompiler(typeRegistry, settingRegistry, apiRegistry,
                        constantRegistry, eventRegistry, javaImportRegistry);
            }
            if (debugService == null) debugService = new DefaultDebugService(logStore, errorStore, profiler, scheduler);
            scriptManager = new DefaultScriptManager(scheduler, compiler, builder.host(), enabledScriptsStore, debugService, templateRegistry);
            if (!enabledScriptsStore.load() && builder.host().logger() != null) builder.host().logger().warn("Failed to load auto-enable state");
            eventRegistry.setDispatcher(this::dispatchEvent);
            eventRegistry.setOverflowHandler(this::failScriptsForEvent);
        }
        return scriptManager;
    }

    @Override
    public LanguageService language() {
        ensureOpen();
        if (languageService == null) {
            languageService = new DefaultLanguageService(apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, javaImportRegistry);
        }
        return languageService;
    }

    @Override
    public DebugService debug() {
        ensureOpen();
        if (debugService == null) {
            if (scheduler == null) {
                scheduler = new ScriptScheduler(builder.limits(), apiRegistry, errorStore, builder.host().workers(), constantRegistry, typeRegistry, builder.host().clock()::nanoTime, builder.host().mainThread()::isMainThread);
            }
            debugService = new DefaultDebugService(logStore, errorStore, profiler, scheduler);
        }
        return debugService;
    }

    @Override
    public void freeze() {
        if (state == VeloraState.FROZEN || state == VeloraState.RUNNING) return;
        if (state == VeloraState.CLOSING || state == VeloraState.CLOSED) throw new IllegalStateException("Engine is closed");
        if (state != VeloraState.CONFIGURING) throw new IllegalStateException("Engine cannot be frozen from state " + state);
        VeloraExtensionContext ctx = new VeloraExtensionContext() {
            @Override public ApiRegistry api() { return apiRegistry; }
            @Override public EventRegistry events() { return eventRegistry; }
            @Override public TypeRegistry types() { return typeRegistry; }
            @Override public SettingRegistry settings() { return settingRegistry; }
            @Override public ConstantRegistry constants() { return constantRegistry; }
            @Override public JavaImportRegistry javaImports() { return javaImportRegistry; }
            @Override public io.velora.api.script.ScriptTemplateRegistry templates() { return templateRegistry; }
            @Override public CategoryRegistry categories() { return categoryRegistry; }
        };
        int apiSnapshot = apiRegistry.all().size();
        int catSnapshot = categoryRegistry.all().size();
        int eventSnapshot = eventRegistry.all().size();
        int typeSnapshot = typeRegistry.all().size();
        int settingSnapshot = settingRegistry.all().size();
        int constantSnapshot = constantRegistry.all().size();
        int javaImportSnapshot = javaImportRegistry.all().size();
        int templateSnapshot = templateRegistry.all().size();
        try {
            for (VeloraExtension ext : extensionRegistry.extensions()) ext.register(ctx);
        } catch (Throwable t) {
            apiRegistry.rollbackTo(apiSnapshot);
            categoryRegistry.rollbackTo(catSnapshot);
            eventRegistry.rollbackTo(eventSnapshot);
            typeRegistry.rollbackTo(typeSnapshot);
            settingRegistry.rollbackTo(settingSnapshot);
            constantRegistry.rollbackTo(constantSnapshot);
            javaImportRegistry.rollbackTo(javaImportSnapshot);
            templateRegistry.rollbackTo(templateSnapshot);
            throw t;
        }
        typeRegistry.freeze();
        settingRegistry.freeze();
        constantRegistry.freeze();
        javaImportRegistry.freeze();
        apiRegistry.freeze();
        extensionRegistry.freeze();
        eventRegistry.freeze();
        templateRegistry.freeze();
        categoryRegistry.freeze();
        if (compiler == null) {
            compiler = new DefaultScriptCompiler(typeRegistry, settingRegistry, apiRegistry,
                    constantRegistry, eventRegistry, javaImportRegistry);
        }
        compiler.freeze();
        state = VeloraState.FROZEN;
    }


    @Override
    public void tick() {
        if (state == VeloraState.CLOSED) {
            throw new IllegalStateException("Engine is closed");
        }
        if (state != VeloraState.FROZEN && state != VeloraState.RUNNING) return;
        if (state == VeloraState.FROZEN) state = VeloraState.RUNNING;

        if (!builder.host().mainThread().isMainThread()) {
            throw new IllegalStateException("tick() must be called from the main thread");
        }

        if (scheduler == null) {
            scheduler = new ScriptScheduler(builder.limits(), apiRegistry, errorStore, builder.host().workers(), constantRegistry, typeRegistry, builder.host().clock()::nanoTime, builder.host().mainThread()::isMainThread);
        }

        logRateLimiter.resetTick();
        long now = builder.host().clock().nanoTime();
        Map<String, io.velora.internal.bytecode.CompiledModule> modules = new HashMap<>();
        Map<String, List<io.velora.api.setting.SettingDescriptor>> scriptSettings = new HashMap<>();

        if (scriptManager != null) {
            for (var inst : scriptManager.repository().all()) {
                if (inst.compiledModule() != null) {
                    modules.put(inst.scriptId(), inst.compiledModule());
                    scriptSettings.put(inst.scriptId(), inst.compiledModule().settings());
                    if (inst.settingStore() != null) {
                        scheduler.setSettingStore(inst.scriptId(), inst.settingStore());
                    }
                }
            }
        }

        // Dispatch pending events to script handlers before running fibers
        eventRegistry.dispatchPending();


        scheduler.tick(now, modules, scriptSettings);
        drainEventHandlers();
    }

    private void dispatchEvent(String eventId, Object payload) {
        EventDescriptor descriptor = eventRegistry.find(eventId);
        if (descriptor == null || scriptManager == null) return;
        String scriptName = descriptor.scriptName();
        ScriptValue eventPayload;
        try {
            eventPayload = VirtualMachine.javaToValue(descriptor.payloadType(), payload);
        } catch (RuntimeException error) {
            eventRegistry.fireDiagnostic(descriptor.id(), EventRegistry.EventDiagnostic.Type.DROPPED);
            builder.host().logger().error("Invalid payload for event " + descriptor.id(), error);
            return;
        }
        for (ScriptInstance instance : scriptManager.repository().all()) {
            if (!instance.enabled() || instance.compiledModule() == null) continue;
            for (var handler : instance.compiledModule().eventHandlers()) {
                if (!matchesEvent(handler.eventReference(), scriptName, eventId)) continue;
                var function = instance.compiledModule().function(handler.functionIndex());
                if (function == null) continue;
                ScriptValue[] args = function.parameterCount() == 0 ? new ScriptValue[0] : new ScriptValue[]{eventPayload};
                scheduleEventHandler(descriptor, instance.scriptId(), handler.functionIndex(), args);
            }
        }
    }

    private void scheduleEventHandler(EventDescriptor descriptor, String scriptId, int functionIndex, ScriptValue[] args) {
        EventHandlerKey key = new EventHandlerKey(scriptId, descriptor.id(), functionIndex);
        Long runningId = runningEventHandlers.get(key);
        boolean running = runningId != null && scheduler.fiber(runningId) != null;
        if (!running && runningId != null) runningEventHandlers.remove(key);
        switch (descriptor.defaultConcurrency()) {
            case PARALLEL -> spawnEvent(descriptor, key, args, false);
            case DROP -> {
                if (running) {
                    profiler.recordDropped(scriptId);
                    eventRegistry.fireDiagnostic(descriptor.id(), EventRegistry.EventDiagnostic.Type.DROPPED);
                } else spawnEvent(descriptor, key, args, true);
            }
            case RESTART -> {
                removePending(key);
                if (!running) {
                    spawnEvent(descriptor, key, args, true);
                    break;
                }
                if (pendingEventCountsByScript.getOrDefault(key.scriptId, 0) >= builder.limits().maxEventQueuePerScript()) {
                    dropEvent(descriptor, key.scriptId);
                    break;
                }
                scheduler.cancelFiber(runningId);
                pendingEventHandlers.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(args);
                incrementPending(key.scriptId);
            }
            case LATEST -> {
                if (running) {
                    Deque<ScriptValue[]> queue = pendingEventHandlers.computeIfAbsent(key, ignored -> new ArrayDeque<>());
                    if (queue.isEmpty()) {
                        if (pendingEventCountsByScript.getOrDefault(key.scriptId, 0) >= builder.limits().maxEventQueuePerScript()) {
                            dropEvent(descriptor, key.scriptId);
                            return;
                        }
                        incrementPending(key.scriptId);
                    }
                    queue.clear();
                    queue.addLast(args);
                    profiler.recordCoalesced(scriptId);
                    eventRegistry.fireDiagnostic(descriptor.id(), EventRegistry.EventDiagnostic.Type.COALESCED);
                } else spawnEvent(descriptor, key, args, true);
            }
            case QUEUE -> {
                if (running) enqueueEvent(descriptor, key, args);
                else spawnEvent(descriptor, key, args, true);
            }
        }
    }

    private void enqueueEvent(EventDescriptor descriptor, EventHandlerKey key, ScriptValue[] args) {
        Deque<ScriptValue[]> queue = pendingEventHandlers.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        int globalDepth = pendingEventCountsByScript.getOrDefault(key.scriptId, 0);
        if (queue.size() < descriptor.queueLimit() && globalDepth < builder.limits().maxEventQueuePerScript()) {
            queue.addLast(args);
            incrementPending(key.scriptId);
            return;
        }
        switch (descriptor.overflowPolicy()) {
            case DROP_NEWEST -> dropEvent(descriptor, key.scriptId);
            case DROP_OLDEST -> {
                if (queue.isEmpty()) {
                    dropEvent(descriptor, key.scriptId);
                    return;
                }
                queue.removeFirst();
                queue.addLast(args);
                dropEvent(descriptor, key.scriptId);
            }
            case KEEP_LATEST -> {
                int removed = queue.size();
                if (removed == 0) {
                    dropEvent(descriptor, key.scriptId);
                    return;
                }
                if (removed > 1) reducePending(key.scriptId, removed - 1);
                queue.clear();
                queue.addLast(args);
                profiler.recordDropped(key.scriptId, removed);
                profiler.recordCoalesced(key.scriptId);
                eventRegistry.fireDiagnostic(descriptor.id(), EventRegistry.EventDiagnostic.Type.COALESCED);
            }
            case COALESCE -> {
                ScriptValue[] previous = queue.pollLast();
                if (previous == null) {
                    dropEvent(descriptor, key.scriptId);
                    return;
                }
                try {
                    Object left = previous.length == 0 ? null : previous[0].boxed();
                    Object right = args.length == 0 ? null : args[0].boxed();
                    Object merged = descriptor.coalescer().apply(left, right);
                    queue.addLast(descriptor.payloadType().equals(VeloraTypes.UNIT)
                            ? args
                            : new ScriptValue[]{VirtualMachine.javaToValue(descriptor.payloadType(), merged)});
                } catch (RuntimeException error) {
                    failScript(key.scriptId, "Event coalescer failed for " + descriptor.id() + ": " + error.getMessage());
                    return;
                }
                profiler.recordCoalesced(key.scriptId);
                eventRegistry.fireDiagnostic(descriptor.id(), EventRegistry.EventDiagnostic.Type.COALESCED);
            }
            case FAIL_SCRIPT -> {
                profiler.recordDropped(key.scriptId);
                failScript(key.scriptId, "Event queue overflow: " + descriptor.id());
            }
        }
    }

    private void dropEvent(EventDescriptor descriptor, String scriptId) {
        profiler.recordDropped(scriptId);
        eventRegistry.fireDiagnostic(descriptor.id(), EventRegistry.EventDiagnostic.Type.DROPPED);
    }

    private void spawnEvent(EventDescriptor descriptor, EventHandlerKey key, ScriptValue[] args, boolean track) {
        var fiber = scheduler.spawnEventFiber(key.scriptId, key.functionIndex, args, descriptor.cost());
        if (fiber == null) {
            profiler.recordDropped(key.scriptId);
            eventRegistry.fireDiagnostic(descriptor.id(), EventRegistry.EventDiagnostic.Type.DROPPED);
            return;
        }
        if (track) runningEventHandlers.put(key, fiber.id());
    }

    private void drainEventHandlers() {
        if (scriptManager == null) return;
        for (EventHandlerKey key : new ArrayList<>(runningEventHandlers.keySet())) {
            Long fiberId = runningEventHandlers.get(key);
            if (fiberId != null && scheduler.fiber(fiberId) != null) continue;
            runningEventHandlers.remove(key);
            Deque<ScriptValue[]> queue = pendingEventHandlers.get(key);
            if (queue == null || queue.isEmpty()) {
                removePending(key);
                continue;
            }
            ScriptInstance instance = scriptManager.repository().get(key.scriptId);
            EventDescriptor descriptor = eventRegistry.find(key.eventId);
            if (instance == null || !instance.enabled() || descriptor == null) {
                removePending(key);
                continue;
            }
            ScriptValue[] args = queue.pollFirst();
            decrementPending(key.scriptId);
            if (queue.isEmpty()) pendingEventHandlers.remove(key);
            spawnEvent(descriptor, key, args, true);
        }
    }

    private void failScriptsForEvent(String eventId) {
        if (scriptManager == null) return;
        EventDescriptor descriptor = eventRegistry.find(eventId);
        if (descriptor == null) return;
        for (ScriptInstance instance : new ArrayList<>(scriptManager.repository().all())) {
            if (instance.enabled() && instance.compiledModule() != null && instance.compiledModule().eventHandlers().stream().anyMatch(handler -> matchesEvent(handler.eventReference(), descriptor.scriptName(), eventId))) {
                profiler.recordDropped(instance.scriptId());
                failScript(instance.scriptId(), "Event queue overflow: " + eventId);
            }
        }
    }

    private void failScript(String scriptId, String message) {
        scriptManager.failRuntime(scriptId, message);
        runningEventHandlers.keySet().removeIf(key -> key.scriptId.equals(scriptId));
        pendingEventHandlers.keySet().removeIf(key -> key.scriptId.equals(scriptId));
        pendingEventCountsByScript.remove(scriptId);
        profiler.recordQueueDepth(scriptId, 0);
    }

    private void incrementPending(String scriptId) {
        int count = pendingEventCountsByScript.merge(scriptId, 1, Integer::sum);
        profiler.recordQueueDepth(scriptId, count);
    }

    private void reducePending(String scriptId, int amount) {
        int count = Math.max(0, pendingEventCountsByScript.getOrDefault(scriptId, 0) - Math.max(0, amount));
        if (count == 0) pendingEventCountsByScript.remove(scriptId);
        else pendingEventCountsByScript.put(scriptId, count);
        profiler.recordQueueDepth(scriptId, count);
    }

    private void decrementPending(String scriptId) {
        int count = pendingEventCountsByScript.getOrDefault(scriptId, 0);
        if (count <= 1) {
            pendingEventCountsByScript.remove(scriptId);
            profiler.recordQueueDepth(scriptId, 0);
        } else {
            pendingEventCountsByScript.put(scriptId, count - 1);
            profiler.recordQueueDepth(scriptId, count - 1);
        }
    }

    private void removePending(EventHandlerKey key) {
        Deque<ScriptValue[]> removed = pendingEventHandlers.remove(key);
        if (removed == null || removed.isEmpty()) return;
        int count = pendingEventCountsByScript.getOrDefault(key.scriptId, 0) - removed.size();
        if (count <= 0) {
            pendingEventCountsByScript.remove(key.scriptId);
            profiler.recordQueueDepth(key.scriptId, 0);
        } else {
            pendingEventCountsByScript.put(key.scriptId, count);
            profiler.recordQueueDepth(key.scriptId, count);
        }
    }

    private static boolean matchesEvent(String reference, String scriptName, String eventId) {
        return reference.equals(scriptName) || reference.equals(eventId);
    }

    private record EventHandlerKey(String scriptId, String eventId, int functionIndex) {}

    @Override
    public void close() {
        if (state == VeloraState.CLOSING || state == VeloraState.CLOSED) return;
        state = VeloraState.CLOSING;
        try {
            if (scriptManager != null) {
                List<String> ids = new ArrayList<>();
                for (var inst : scriptManager.repository().all()) ids.add(inst.scriptId());
                for (String id : ids) {
                    try {
                        var result = scriptManager.unload(id);
                        if (result.isFailure()) logCloseFailure("Failed to unload script " + id + ": " + result.message(), result.cause());
                    } catch (Throwable error) {
                        logCloseFailure("Failed to unload script " + id, error);
                    }
                }
            }
        } finally {
            try {
                if (scheduler != null) scheduler.cancellationTree().clear();
            } finally {
                try {
                    eventRegistry.close();
                } finally {
                    try {
                        javaImportRegistry.close();
                    } finally {
                        try {
                            builder.host().workers().shutdown();
                        } finally {
                            state = VeloraState.CLOSED;
                        }
                    }
                }
            }
        }
    }

    private void ensureOpen() {
        if (state == VeloraState.CLOSING || state == VeloraState.CLOSED) throw new IllegalStateException("Engine is closed");
    }

    private void logCloseFailure(String message, Throwable error) {
        try {
            if (error != null) builder.host().logger().error(message, error);
            else builder.host().logger().warn(message);
        } catch (Throwable ignored) { }
    }

    private static int findFunctionIndex(io.velora.internal.bytecode.CompiledModule module, String name) {
        for (int i = 0; i < module.functions().size(); i++) {
            if (module.function(i).name().equals(name)) return i;
        }
        return -1;
    }
}
