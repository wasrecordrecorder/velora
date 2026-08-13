package io.velora.api.registry;

import io.velora.api.permission.ScriptPermission;

import java.util.Collection;
import java.util.List;

/**
 * Registry for script permissions.
 */
public interface PermissionRegistry {

    void register(ScriptPermission permission);

    ScriptPermission find(String id);

    List<ScriptPermission> all();

    Collection<String> ids();

    boolean isFrozen();
}
