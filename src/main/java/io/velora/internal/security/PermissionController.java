package io.velora.internal.security;

import io.velora.api.permission.PermissionSet;
import io.velora.api.permission.ScriptPermission;

import java.util.*;

public final class PermissionController {
    private final Map<String, PermissionSet> scriptPermissions = new HashMap<>();

    public void setPermissions(String scriptId, PermissionSet permissions) {
        scriptPermissions.put(scriptId, permissions);
    }

    public boolean hasPermission(String scriptId, ScriptPermission permission) {
        PermissionSet set = scriptPermissions.get(scriptId);
        return set != null && set.contains(permission);
    }

    public PermissionSet getPermissions(String scriptId) {
        return scriptPermissions.getOrDefault(scriptId, PermissionSet.empty());
    }

    public void clear(String scriptId) {
        scriptPermissions.remove(scriptId);
    }
}
