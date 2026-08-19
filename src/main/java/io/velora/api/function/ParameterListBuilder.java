package io.velora.api.function;

import io.velora.api.type.VeloraType;

import java.util.ArrayList;
import java.util.List;

public final class ParameterListBuilder {

    private final List<ParameterDescriptor> params = new ArrayList<>();

    public ParameterListBuilder required(String name, VeloraType type) {
        return required(name, type, "");
    }

    public ParameterListBuilder required(String name, VeloraType type, String description) {
        params.add(ParameterDescriptor.required(name, type, description));
        return this;
    }

    public ParameterListBuilder optional(String name, VeloraType type, Object defaultValue) {
        return optional(name, type, defaultValue, "");
    }

    public ParameterListBuilder optional(String name, VeloraType type, Object defaultValue, String description) {
        params.add(ParameterDescriptor.optional(name, type, defaultValue, description));
        return this;
    }

    public ParameterListBuilder variadic(String name, VeloraType type) {
        return variadic(name, type, "");
    }

    public ParameterListBuilder variadic(String name, VeloraType type, String description) {
        params.add(ParameterDescriptor.variadic(name, type, description));
        return this;
    }

    public List<ParameterDescriptor> build() {
        return List.copyOf(params);
    }

    @FunctionalInterface
    public interface Spec {
        ParameterListBuilder apply(ParameterListBuilder builder);
    }
}
