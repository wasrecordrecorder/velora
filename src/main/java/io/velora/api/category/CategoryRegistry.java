package io.velora.api.category;

import java.util.List;
import java.util.Optional;

public interface CategoryRegistry {
    void register(ApiCategory category);
    Optional<ApiCategory> byId(String id);
    List<ApiCategory> all();
    void freeze();
}
