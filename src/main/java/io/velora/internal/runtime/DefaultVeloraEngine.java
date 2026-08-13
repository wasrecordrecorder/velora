package io.velora.internal.runtime;

import io.velora.api.*;
import io.velora.api.category.ApiCategory;
import io.velora.api.category.CategoryRegistry;
import io.velora.api.compiler.ScriptCompiler;
import io.velora.api.debug.DebugService;
import io.velora.api.event.EventRegistry;
import io.velora.api.event.EventDescriptor;
import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.language.LanguageService;
import io.velora.api.registry.*;
import io.velora.api.script.ScriptManager;
import io.velora.api.permission.ScriptPermission;
import io.velora.api.setting.SettingKind;
import io.velora.api.type.VeloraTypes;
import io.velora.host.VeloraHost;
import io.velora.internal.debug.*;
import io.velora.internal.event.DefaultEventRegistry;
import io.velora.internal.language.DefaultLanguageService;
import io.velora.internal.scheduler.ScriptScheduler;
import io.velora.internal.setting.DefaultSettingRegistry;
import io.velora.internal.security.*;
import io.velora.internal.persistence.EnabledScriptsStore;
import io.velora.internal.vm.ScriptValue;

import java.util.*;

public final class DefaultVeloraEngine implements VeloraEngine {

    private final VeloraEngineBuilder builder;
    private final DefaultTypeRegistry typeRegistry;
    private final DefaultSettingRegistry settingRegistry;
    private final DefaultPermissionRegistry permissionRegistry;
    private final DefaultConstantRegistry constantRegistry;
    private final DefaultApiRegistry apiRegistry;
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
    private final PermissionController permissionController = new PermissionController();
    private final EnabledScriptsStore enabledScriptsStore;
    private final HandleValidator handleValidator = new HandleValidator();
    private final ApiCostController apiCostController = new ApiCostController(1000);
    private final ScriptThrottleController scriptThrottleController = new ScriptThrottleController(10);
    private final LogRateLimiter logRateLimiter = new LogRateLimiter(100);
    private final int compilerThreads;
    private final int ioThreads;
    private final Map<String, Integer> eventsDeliveredTotal = new HashMap<>();
    private VeloraState state = VeloraState.CREATED;

    public void resetEventCounter(String scriptId) {
        eventsDeliveredTotal.remove(scriptId);
    }

    private DefaultVeloraEngine(VeloraEngineBuilder builder) {
        this.builder = builder;
        this.compilerThreads = builder.compilerThreads();
        this.ioThreads = builder.ioThreads();
        this.enabledScriptsStore = new EnabledScriptsStore(builder.host().fileSystem());
        this.typeRegistry = new DefaultTypeRegistry();
        this.settingRegistry = new DefaultSettingRegistry();
        this.permissionRegistry = new DefaultPermissionRegistry();
        this.constantRegistry = new DefaultConstantRegistry();
        this.apiRegistry = new DefaultApiRegistry();
        registerBuiltInApi();
        registerBuiltInSettings();
        this.extensionRegistry = new DefaultExtensionRegistry();
        this.eventRegistry = new DefaultEventRegistry(builder.host());
        this.templateRegistry = new DefaultScriptTemplateRegistry();
        this.categoryRegistry = new DefaultCategoryRegistry();
        // Register built-in categories
        categoryRegistry.register(new ApiCategory("core", "Core", "Core engine functionality"));
        categoryRegistry.register(new ApiCategory("bot", "Bot", "Bot control and automation"));
        categoryRegistry.register(new ApiCategory("player", "Player", "Player interaction and information"));
        categoryRegistry.register(new ApiCategory("world", "World", "World queries and manipulation"));
        categoryRegistry.register(new ApiCategory("log", "Logging", "Logging utilities"));
        categoryRegistry.register(new ApiCategory("settings", "Settings", "Script settings and configuration"));
        categoryRegistry.register(new ApiCategory("permissions", "Permissions", "Permission management"));
        this.state = VeloraState.CONFIGURING;
    }

    public static DefaultVeloraEngine create(VeloraEngineBuilder builder) {
        return new DefaultVeloraEngine(builder);
    }

    private void registerBuiltInApi() {
        io.velora.host.VeloraLogger hostLogger = builder.host().logger();
        apiRegistry.namespace("log", ns -> {
            ns.function("info", io.velora.api.type.VeloraTypes.UNIT, ctx -> { hostLogger.info(String.valueOf(ctx.argument(0))); return null; })
                .description("Logs an informational message").categoryId("log");
            ns.function("warn", io.velora.api.type.VeloraTypes.UNIT, ctx -> { hostLogger.warn(String.valueOf(ctx.argument(0))); return null; })
                .description("Logs a warning message").categoryId("log");
            ns.function("error", io.velora.api.type.VeloraTypes.UNIT, ctx -> { hostLogger.error(String.valueOf(ctx.argument(0)), null); return null; })
                .description("Logs an error message").categoryId("log");
            ns.function("debug", io.velora.api.type.VeloraTypes.UNIT, ctx -> { hostLogger.debug(String.valueOf(ctx.argument(0))); return null; })
                .description("Logs a debug message").categoryId("log");
        });
        ScriptPermission playerControl = ScriptPermission.of("PLAYER_CONTROL", "Player control", "Allows controlling the player entity (movement, actions)");
        ScriptPermission worldRead = ScriptPermission.of("WORLD_READ", "World read", "Allows reading world data (players, blocks)");
        permissionRegistry.register(playerControl);
        permissionRegistry.register(worldRead);
        permissionRegistry.register(ScriptPermission.of("LOCAL_STORAGE", "Local storage", "Allows reading/writing local persistent storage"));

        apiRegistry.namespace("player", ns -> {
            ns.property("position", io.velora.api.type.VeloraTypes.VEC3, playerControl, ctx -> new double[]{0.0, 64.0, 0.0}, "Current player position").categoryId("player");
        });
        apiRegistry.namespace("bot", ns -> {
            ns.property("isBusy", io.velora.api.type.VeloraTypes.BOOLEAN, playerControl, ctx -> false, "Whether the bot is currently busy").categoryId("bot");
            ns.function("cancelAll", io.velora.api.type.VeloraTypes.UNIT, playerControl, ctx -> null).description("Cancels all bot actions").categoryId("bot");
        });
        apiRegistry.namespace("world", ns -> {
        });
        apiRegistry.register(FunctionDescriptor.builder()
                .namespace("world").name("findNearestPlayer")
                .parameter("radius", io.velora.api.type.VeloraTypes.DOUBLE)
                .returns(io.velora.api.type.VeloraTypes.VEC3)
                .permission(worldRead)
                .invoker(ctx -> new double[]{0.0, 64.0, 0.0})
                .description("Finds the nearest player within the given radius")
                .categoryId("world")
                .build());
        apiRegistry.register(FunctionDescriptor.builder()
                .namespace("world").name("findNearestBlock")
                .parameter("target", io.velora.api.type.VeloraTypes.STRING)
                .parameter("radius", io.velora.api.type.VeloraTypes.INT)
                .returns(io.velora.api.type.VeloraTypes.VEC3)
                .permission(worldRead)
                .invoker(ctx -> new double[]{0.0, 64.0, 0.0})
                .description("Finds the nearest block of the given type within the radius")
                .categoryId("world")
                .build());
        apiRegistry.register(FunctionDescriptor.builder()
                .namespace("bot").name("moveTo")
                .parameter("target", io.velora.api.type.VeloraTypes.UNIT)
                .returns(io.velora.api.type.VeloraTypes.UNIT)
                .permission(playerControl)
                .invoker(ctx -> null)
                .description("Moves the bot to the target position")
                .categoryId("bot")
                .build());
        apiRegistry.register(FunctionDescriptor.builder()
                .namespace("bot").name("lookAt")
                .parameter("target", io.velora.api.type.VeloraTypes.UNIT)
                .returns(io.velora.api.type.VeloraTypes.UNIT)
                .permission(playerControl)
                .invoker(ctx -> null)
                .description("Makes the bot look at the target position")
                .categoryId("bot")
                .build());
        apiRegistry.register(FunctionDescriptor.builder()
                .namespace("bot").name("mine")
                .parameter("target", io.velora.api.type.VeloraTypes.UNIT)
                .returns(io.velora.api.type.VeloraTypes.UNIT)
                .permission(playerControl)
                .invoker(ctx -> null)
                .description("Mines the target block")
                .categoryId("bot")
                .build());
        for (FunctionDescriptor fd : apiRegistry.all()) {
            apiRegistry.markBuiltIn(fd.namespace(), fd.name());
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
    public PermissionRegistry permissions() { return permissionRegistry; }

    @Override
    public ConstantRegistry constants() { return constantRegistry; }

    @Override
    public VeloraExtensionRegistry extensions() { return extensionRegistry; }

    @Override
    public CategoryRegistry categories() { return categoryRegistry; }

    @Override
    public ScriptCompiler compiler() {
        if (compiler == null) {
            compiler = new DefaultScriptCompiler(typeRegistry, settingRegistry, apiRegistry,
                    constantRegistry, permissionRegistry);
        }
        return compiler;
    }

    @Override
    public ScriptManager scripts() {
        if (scriptManager == null) {
            if (scheduler == null) {
                scheduler = new ScriptScheduler(builder.limits(), apiRegistry, errorStore);
            }
            if (compiler == null) {
                compiler = new DefaultScriptCompiler(typeRegistry, settingRegistry, apiRegistry,
                        constantRegistry, permissionRegistry);
            }
            scriptManager = new DefaultScriptManager(scheduler, compiler, builder.host(), permissionController, enabledScriptsStore, handleValidator, scriptId -> eventsDeliveredTotal.remove(scriptId));
            enabledScriptsStore.load();
            // Wire up event dispatch: when events are emitted, find matching handlers in enabled scripts
            eventRegistry.setDispatcher((eventId, payload) -> {
                EventDescriptor desc = eventRegistry.find(eventId);
                if (desc == null) return;
                String scriptName = desc.scriptName();
                for (var inst : scriptManager.repository().all()) {
                    if (!inst.enabled()) continue;
                    var module = inst.compiledModule();
                    if (module == null) continue;
                    // Check event permission: if event has a permission, script must have it granted
                    if (desc.permission() != null && !scriptManager.hasPermissionGrant(inst.scriptId(), io.velora.api.permission.PermissionSet.of(desc.permission()))) {
                        continue;
                    }
                    // Check per-script event queue limit (global, not per-tick)
                    int delivered = eventsDeliveredTotal.getOrDefault(inst.scriptId(), 0);
                    if (delivered >= builder.limits().maxEventQueuePerScript()) {
                        continue;
                    }
                    for (var handler : module.eventHandlers()) {
                        String ref = handler.eventReference();
                        boolean matches = ref.equals(scriptName)
                                || ref.equals(eventId)
                                || (ref.startsWith("Event.") && ref.substring("Event.".length()).equals(scriptName));
                        if (matches) {
                            eventsDeliveredTotal.put(inst.scriptId(), delivered + 1);
                            ScriptValue[] args = new ScriptValue[]{ScriptValue.fromJava(payload)};
                            scheduler.spawnFiber(inst.scriptId(), handler.functionIndex(), args);
                        }
                    }
                }
            });
        }
        return scriptManager;
    }

    @Override
    public LanguageService language() {
        if (languageService == null) {
            languageService = new DefaultLanguageService();
        }
        return languageService;
    }

    @Override
    public DebugService debug() {
        if (debugService == null) {
            if (scheduler == null) {
                scheduler = new ScriptScheduler(builder.limits(), apiRegistry, errorStore);
            }
            debugService = new DefaultDebugService(
                    new ScriptLogStore(1000),
                    errorStore,
                    profiler,
                    scheduler
            );
        }
        return debugService;
    }

    @Override
    public void freeze() {
        if (state == VeloraState.CLOSED) {
            throw new IllegalStateException("Engine is closed");
        }
        VeloraExtensionContext ctx = new VeloraExtensionContext() {
            @Override public ApiRegistry api() { return apiRegistry; }
            @Override public EventRegistry events() { return eventRegistry; }
            @Override public TypeRegistry types() { return typeRegistry; }
            @Override public SettingRegistry settings() { return settingRegistry; }
            @Override public PermissionRegistry permissions() { return permissionRegistry; }
            @Override public ConstantRegistry constants() { return constantRegistry; }
            @Override public io.velora.api.script.ScriptTemplateRegistry templates() { return templateRegistry; }
            @Override public CategoryRegistry categories() { return categoryRegistry; }
        };
        for (VeloraExtension ext : extensionRegistry.extensions()) {
            int apiSnapshot = apiRegistry.all().size();
            int catSnapshot = categoryRegistry.all().size();
            int permSnapshot = permissionRegistry.all().size();
            int eventSnapshot = eventRegistry.all().size();
            try {
                ext.register(ctx);
            } catch (Throwable t) {
                apiRegistry.rollbackTo(apiSnapshot);
                categoryRegistry.rollbackTo(catSnapshot);
                permissionRegistry.rollbackTo(permSnapshot);
                eventRegistry.rollbackTo(eventSnapshot);
                throw t;
            }
        }
        typeRegistry.freeze();
        settingRegistry.freeze();
        permissionRegistry.freeze();
        constantRegistry.freeze();
        apiRegistry.freeze();
        extensionRegistry.freeze();
        eventRegistry.freeze();
        templateRegistry.freeze();
        categoryRegistry.freeze();
        if (compiler == null) {
            compiler = new DefaultScriptCompiler(typeRegistry, settingRegistry, apiRegistry,
                    constantRegistry, permissionRegistry);
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
            scheduler = new ScriptScheduler(builder.limits(), apiRegistry, errorStore);
        }

        long now = System.nanoTime();
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

        // Dispatch ON_TICK lifecycle hook to enabled scripts
        if (scriptManager != null) {
            for (var inst : scriptManager.repository().all()) {
                if (!inst.enabled()) continue;
                var module = inst.compiledModule();
                if (module == null) continue;
                if (module.lifecycleHooks().contains("ON_TICK")) {
                    int fnIdx = findFunctionIndex(module, "ON_TICK");
                    if (fnIdx >= 0) {
                        scheduler.spawnFiber(inst.scriptId(), fnIdx, new ScriptValue[0]);
                    }
                }
            }
        }

        // Record event queue depth metrics to profiler
        profiler.recordMaxQueueDepth(eventRegistry.maxQueueDepth());
        profiler.setDroppedEvents(eventRegistry.droppedEvents());
        profiler.setCoalescedEvents(eventRegistry.coalescedEvents());

        scheduler.tick(now, modules, scriptSettings);
        profiler.recordInstructions(scheduler.metrics().lastTickInstructions());
    }

    @Override
    public void close() {
        if (state == VeloraState.CLOSED) return;
        state = VeloraState.CLOSING;
        if (scriptManager != null) {
            List<String> ids = new ArrayList<>();
            for (var inst : scriptManager.repository().all()) {
                ids.add(inst.scriptId());
            }
            for (String id : ids) {
                scriptManager.unload(id);
            }
        }
        if (scheduler != null) {
            scheduler.cancellationTree().clear();
        }
        builder.host().workers().shutdown();
        state = VeloraState.CLOSED;
    }

    private static int findFunctionIndex(io.velora.internal.bytecode.CompiledModule module, String name) {
        for (int i = 0; i < module.functions().size(); i++) {
            if (module.function(i).name().equals(name)) return i;
        }
        return -1;
    }
}
