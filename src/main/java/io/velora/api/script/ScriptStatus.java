package io.velora.api.script;

public enum ScriptStatus {
    DISCOVERED,
    COMPILING,
    LOADED,
    ENABLING,
    ENABLED,
    DISABLING,
    DISABLED,
    RELOADING,
    FAILED,
    THROTTLED,
    UNLOADED;

    public boolean isRunning() {
        return this == ENABLED || this == ENABLING;
    }

    public boolean isTerminal() {
        return this == DISABLED || this == UNLOADED || this == FAILED;
    }
}
