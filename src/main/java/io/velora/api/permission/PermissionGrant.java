package io.velora.api.permission;

import java.util.Objects;

public record PermissionGrant(
        String scriptId,
        PermissionSet permissions,
        String permissionSetHash,
        long grantedAtNanos
) {
    public PermissionGrant {
        Objects.requireNonNull(scriptId);
        Objects.requireNonNull(permissions);
        Objects.requireNonNull(permissionSetHash);
    }

    public static PermissionGrant of(String scriptId, PermissionSet permissions) {
        return new PermissionGrant(scriptId, permissions, permissions.hash(), System.nanoTime());
    }

    public boolean covers(PermissionSet required) {
        return permissions.containsAll(required);
    }
}
