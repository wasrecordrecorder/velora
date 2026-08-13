package io.velora.api.category;

import java.util.Objects;

public final class ApiCategory {
    private final String id;
    private final String displayName;
    private final String description;

    public ApiCategory(String id, String displayName, String description) {
        this.id = Objects.requireNonNull(id);
        this.displayName = displayName;
        this.description = description;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String description() { return description; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiCategory c)) return false;
        return id.equals(c.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() { return "ApiCategory(" + id + ")"; }
}
