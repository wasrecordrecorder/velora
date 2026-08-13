package io.velora.api.permission;

import java.util.Objects;

public record PermissionRequest(
        String scriptId,
        PermissionSet required,
        PermissionSet maximum,
        PermissionSet missing
) {
    public PermissionRequest {
        Objects.requireNonNull(scriptId);
        Objects.requireNonNull(required);
    }

    public static PermissionRequest of(String scriptId, PermissionSet required, PermissionSet maximum) {
        PermissionSet missing = PermissionSet.empty();
        for (ScriptPermission p : required.all()) {
            if (!maximum.contains(p)) {
                missing = missing.union(PermissionSet.of(p));
            }
        }
        return new PermissionRequest(scriptId, required, maximum, missing);
    }

    public boolean hasMissing() {
        return !missing.isEmpty();
    }

    public boolean isWithinMaximum() {
        return maximum.containsAll(required);
    }
}
