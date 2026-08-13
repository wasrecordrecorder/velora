package io.velora.internal.script;

import io.velora.api.script.ScriptDescriptor;
import io.velora.api.script.ScriptStatus;

import java.util.*;

public final class ScriptInstance {
    private final String scriptId;
    private ScriptDescriptor descriptor;
    private final ScriptStatusMachine statusMachine = new ScriptStatusMachine();
    private io.velora.internal.bytecode.CompiledModule compiledModule;
    private io.velora.internal.setting.SettingStore settingStore;
    private long revision;
    private Throwable lastError;

    public ScriptInstance(String scriptId, ScriptDescriptor descriptor) {
        this.scriptId = scriptId;
        this.descriptor = descriptor;
    }

    public String scriptId() { return scriptId; }
    public ScriptDescriptor descriptor() { return descriptor; }
    public void descriptor(ScriptDescriptor d) { this.descriptor = d; }
    public ScriptStatus status() { return statusMachine.status(); }
    public ScriptStatusMachine statusMachine() { return statusMachine; }
    public io.velora.internal.bytecode.CompiledModule compiledModule() { return compiledModule; }
    public void compiledModule(io.velora.internal.bytecode.CompiledModule m) { this.compiledModule = m; }
    public io.velora.internal.setting.SettingStore settingStore() { return settingStore; }
    public void settingStore(io.velora.internal.setting.SettingStore s) { this.settingStore = s; }
    public long revision() { return revision; }
    public void revision(long r) { this.revision = r; }
    public Throwable lastError() { return lastError; }
    public void lastError(Throwable e) { this.lastError = e; }
    public boolean enabled() { return statusMachine.isRunning(); }
}
