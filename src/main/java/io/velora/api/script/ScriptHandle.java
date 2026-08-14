package io.velora.api.script;

public interface ScriptHandle {

    String id();

    ScriptDescriptor descriptor();

    ScriptStatus status();

    boolean enabled();

    ScriptOperationResult enable();

    ScriptOperationResult disable();

    ScriptOperationResult toggle();

    ScriptOperationResult reload();

    io.velora.api.setting.SettingSchema settings();

    java.util.Map<String, io.velora.api.setting.SettingValue> settingValues();

    io.velora.api.debug.DebugSnapshot debug();
}
