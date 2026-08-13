package io.velora.api.function;

import io.velora.api.type.VeloraType;

import java.util.*;
import java.util.function.Function;

/**
 * Builder for parameter lists.
 */
public final class ParameterListBuilder {

    private final List<ParameterDescriptor> params = new ArrayList<>();

    public ParameterListBuilder required(String name, VeloraType type) {
        params.add(ParameterDescriptor.required(name, type));
        return this;
    }

    public ParameterListBuilder optional(String name, VeloraType type, Object defaultValue) {
        params.add(ParameterDescriptor.optional(name, type, defaultValue));
        return this;
    }

    public List<ParameterDescriptor> build() {
        return List.copyOf(params);
    }

    /**
     * Functional interface for building parameter lists.
     */
    @FunctionalInterface
    public interface Spec {
        ParameterListBuilder apply(ParameterListBuilder builder);
    }
}
