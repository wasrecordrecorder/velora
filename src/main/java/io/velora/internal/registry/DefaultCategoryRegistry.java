package io.velora.internal.registry;

import io.velora.api.category.ApiCategory;
import io.velora.api.category.CategoryRegistry;

import java.util.*;

public final class DefaultCategoryRegistry implements CategoryRegistry {
    private final Map<String, ApiCategory> categories = new LinkedHashMap<>();
    private boolean frozen = false;

    @Override
    public void register(ApiCategory category) {
        if (frozen) throw new IllegalStateException("Registry is frozen");
        if (categories.containsKey(category.id())) {
            throw new IllegalStateException("Duplicate category: " + category.id());
        }
        categories.put(category.id(), category);
    }

    @Override
    public Optional<ApiCategory> byId(String id) {
        return Optional.ofNullable(categories.get(id));
    }

    @Override
    public List<ApiCategory> all() {
        return List.copyOf(categories.values());
    }

    @Override
    public void freeze() {
        frozen = true;
    }

    public void rollbackTo(int snapshotSize) {
        if (categories.size() <= snapshotSize) return;
        List<String> keys = new ArrayList<>(categories.keySet());
        for (int i = keys.size() - 1; i >= snapshotSize; i--) {
            categories.remove(keys.get(i));
        }
    }
}
