package io.velora.api.permission;

import java.util.*;

/**
 * An immutable set of permissions.
 */
public final class PermissionSet {

    private final Set<ScriptPermission> permissions;

    private PermissionSet(Set<ScriptPermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public static PermissionSet of(ScriptPermission... perms) {
        return new PermissionSet(Set.of(perms));
    }

    public static PermissionSet of(Collection<ScriptPermission> perms) {
        return new PermissionSet(new LinkedHashSet<>(perms));
    }

    public static PermissionSet empty() {
        return new PermissionSet(Set.of());
    }

    public boolean contains(ScriptPermission permission) {
        return permissions.contains(permission);
    }

    public boolean containsAll(PermissionSet other) {
        return permissions.containsAll(other.permissions);
    }

    public PermissionSet union(PermissionSet other) {
        Set<ScriptPermission> result = new LinkedHashSet<>(this.permissions);
        result.addAll(other.permissions);
        return new PermissionSet(result);
    }

    public Set<ScriptPermission> all() {
        return permissions;
    }

    public boolean isEmpty() {
        return permissions.isEmpty();
    }

    public String hash() {
        List<String> ids = new ArrayList<>();
        for (ScriptPermission p : permissions) {
            ids.add(p.id());
        }
        Collections.sort(ids);
        return Integer.toHexString(ids.hashCode());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PermissionSet ps)) return false;
        return permissions.equals(ps.permissions);
    }

    @Override
    public int hashCode() {
        return permissions.hashCode();
    }
}
