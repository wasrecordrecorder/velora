package io.velora.api.permission;

import java.util.Objects;

/**
 * A script permission. Permissions are the sole source of access control.
 */
public final class ScriptPermission {

    private final String id;
    private final String displayName;
    private final String description;
    private final String categoryId;
    private final String extensionId;

    private ScriptPermission(String id, String displayName, String description, String categoryId, String extensionId) {
        this.id = Objects.requireNonNull(id);
        if (id.isBlank()) throw new IllegalArgumentException("Permission id cannot be blank");
        this.displayName = displayName == null ? "" : displayName;
        this.description = description == null ? "" : description;
        this.categoryId = categoryId;
        this.extensionId = extensionId;
    }

    public static ScriptPermission of(String id, String displayName, String description) {
        return new ScriptPermission(id, displayName, description, "", "");
    }

    public static ScriptPermission of(String id, String displayName, String description, String categoryId, String extensionId) {
        return new ScriptPermission(id, displayName, description, categoryId, extensionId);
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public String categoryId() { return categoryId; }
    public String extensionId() { return extensionId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScriptPermission p)) return false;
        return id.equals(p.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Permission(" + id + ")";
    }
}
