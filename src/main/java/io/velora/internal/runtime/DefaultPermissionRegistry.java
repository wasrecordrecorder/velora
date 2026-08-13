package io.velora.internal.runtime;

import io.velora.api.permission.ScriptPermission;
import io.velora.api.registry.PermissionRegistry;

import java.util.*;

public final class DefaultPermissionRegistry implements PermissionRegistry {

    private final Map<String, ScriptPermission> byId = new LinkedHashMap<>();
    private boolean frozen;

    @Override
    public void register(ScriptPermission permission) {
        checkFrozen();
        if (byId.containsKey(permission.id())) {
            return;
        }
        byId.put(permission.id(), permission);
    }

    @Override
    public ScriptPermission find(String id) {
        return byId.get(id);
    }

    @Override
    public List<ScriptPermission> all() {
        return List.copyOf(byId.values());
    }

    @Override
    public Collection<String> ids() {
        return Collections.unmodifiableSet(byId.keySet());
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    void freeze() {
        frozen = true;
    }

    void rollbackTo(int snapshotSize) {
        if (byId.size() <= snapshotSize) return;
        List<String> keys = new ArrayList<>(byId.keySet());
        for (int i = keys.size() - 1; i >= snapshotSize; i--) {
            byId.remove(keys.get(i));
        }
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("PermissionRegistry is frozen");
    }
}
