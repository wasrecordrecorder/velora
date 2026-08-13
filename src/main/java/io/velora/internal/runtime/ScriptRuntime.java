package io.velora.internal.runtime;

import io.velora.api.setting.SettingDescriptor;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.scheduler.ScriptFiber;
import io.velora.internal.scheduler.ScriptScheduler;
import io.velora.internal.vm.ScriptValue;

import java.util.*;

public final class ScriptRuntime {
    private final ScriptInstance instance;
    private final ScriptScheduler scheduler;
    private long rootFiberId = -1;

    public ScriptRuntime(ScriptInstance instance, ScriptScheduler scheduler) {
        this.instance = instance;
        this.scheduler = scheduler;
    }

    public ScriptInstance instance() { return instance; }

    public void start() {
        CompiledModule module = instance.compiledModule();
        if (module == null) return;
        for (String hook : module.lifecycleHooks()) {
            if (hook.equals("ON_RUN")) {
                int fnIdx = findFunctionIndex(module, "ON_RUN");
                if (fnIdx >= 0) {
                    ScriptFiber fiber = scheduler.spawnFiber(instance.scriptId(), fnIdx, new ScriptValue[0]);
                    rootFiberId = fiber.id();
                }
            }
        }
    }

    public void stop() {
        if (rootFiberId >= 0) {
            scheduler.cancelFiber(rootFiberId);
        }
        scheduler.cancelScriptTasks(instance.scriptId());
    }

    private int findFunctionIndex(CompiledModule module, String name) {
        for (int i = 0; i < module.functions().size(); i++) {
            if (module.function(i).name().equals(name)) return i;
        }
        return -1;
    }

    public List<SettingDescriptor> settings() {
        CompiledModule module = instance.compiledModule();
        return module != null ? module.settings() : List.of();
    }
}
