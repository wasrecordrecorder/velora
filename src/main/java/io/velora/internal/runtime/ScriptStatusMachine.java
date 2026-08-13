package io.velora.internal.runtime;

public final class ScriptStatusMachine {
    private io.velora.api.script.ScriptStatus status = io.velora.api.script.ScriptStatus.DISCOVERED;

    public io.velora.api.script.ScriptStatus status() { return status; }

    public void transition(io.velora.api.script.ScriptStatus newStatus) {
        this.status = newStatus;
    }

    public boolean canEnable() {
        return status == io.velora.api.script.ScriptStatus.LOADED
                || status == io.velora.api.script.ScriptStatus.DISABLED;
    }

    public boolean canDisable() {
        return status == io.velora.api.script.ScriptStatus.ENABLED;
    }

    public boolean canReload() {
        return status != io.velora.api.script.ScriptStatus.UNLOADED
                && status != io.velora.api.script.ScriptStatus.DISCOVERED;
    }

    public boolean isRunning() { return status.isRunning(); }
    public boolean isTerminal() { return status.isTerminal(); }
}
