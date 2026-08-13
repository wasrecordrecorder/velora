package io.velora.api.script;

import java.util.Optional;

public interface ScriptManager {

    java.util.List<ScriptDescriptor> list();

    Optional<ScriptHandle> find(String scriptId);

    ScriptOperationResult enable(String scriptId);

    ScriptOperationResult disable(String scriptId);

    ScriptOperationResult toggle(String scriptId);

    ScriptOperationResult reload(String scriptId);

    ScriptOperationResult unload(String scriptId);

    boolean isEnabled(String scriptId);

    ScriptStatus status(String scriptId);

    io.velora.api.setting.SettingSchema settings(String scriptId);

    ScriptTransaction beginTransaction(String scriptId);

    ScriptServiceEvents events();

    void discover();

    void loadEnabled();

    void grantPermissions(String scriptId, io.velora.api.permission.PermissionSet set);

    void revokePermissions(String scriptId, io.velora.api.permission.PermissionSet set);

    boolean hasPermissionGrant(String scriptId, io.velora.api.permission.PermissionSet required);
}
