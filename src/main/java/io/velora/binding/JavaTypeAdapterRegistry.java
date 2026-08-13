package io.velora.binding;

import io.velora.api.type.TypeAdapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class JavaTypeAdapterRegistry {

    private final Map<Class<?>, TypeAdapter<?>> adapters = new ConcurrentHashMap<>();

    public <T> void register(Class<T> javaType, TypeAdapter<T> adapter) {
        adapters.put(javaType, adapter);
    }

    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> find(Class<T> javaType) {
        return (TypeAdapter<T>) adapters.get(javaType);
    }

    public boolean hasAdapter(Class<?> javaType) {
        return adapters.containsKey(javaType);
    }

    public int size() {
        return adapters.size();
    }
}
