package io.velora.api.function;

import io.velora.api.permission.ScriptPermission;
import io.velora.api.type.VeloraType;

/**
 * Descriptor for a function parameter.
 */
public record ParameterDescriptor(
        String name,
        VeloraType type,
        boolean required,
        boolean hasDefault,
        Object defaultValue
) {
    public ParameterDescriptor {
        java.util.Objects.requireNonNull(name);
        java.util.Objects.requireNonNull(type);
    }

    public static ParameterDescriptor required(String name, VeloraType type) {
        return new ParameterDescriptor(name, type, true, false, null);
    }

    public static ParameterDescriptor optional(String name, VeloraType type, Object defaultValue) {
        return new ParameterDescriptor(name, type, false, true, defaultValue);
    }
}
