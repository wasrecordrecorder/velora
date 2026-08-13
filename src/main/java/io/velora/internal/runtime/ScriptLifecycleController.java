package io.velora.internal.runtime;

import io.velora.api.script.ScriptStatus;

public final class ScriptLifecycleController {

    private final ScriptInstance instance;

    public ScriptLifecycleController(ScriptInstance instance) {
        this.instance = instance;
    }

    public void onLoad() {
        instance.statusMachine().transition(ScriptStatus.LOADED);
    }

    public void onEnable() {
        instance.statusMachine().transition(ScriptStatus.ENABLING);
        instance.statusMachine().transition(ScriptStatus.ENABLED);
    }

    public void onDisable() {
        instance.statusMachine().transition(ScriptStatus.DISABLING);
        instance.statusMachine().transition(ScriptStatus.DISABLED);
    }

    public void onUnload() {
        instance.statusMachine().transition(ScriptStatus.UNLOADED);
    }

    public void onReload() {
        instance.statusMachine().transition(ScriptStatus.RELOADING);
    }

    public void onFailed(Throwable error) {
        instance.lastError(error);
        instance.statusMachine().transition(ScriptStatus.FAILED);
    }
}
