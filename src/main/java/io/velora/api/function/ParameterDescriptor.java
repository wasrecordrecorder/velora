package io.velora.api.function;

import io.velora.api.type.VeloraType;

public record ParameterDescriptor(
        String name,
        VeloraType type,
        boolean required,
        boolean hasDefault,
        Object defaultValue,
        boolean variadic,
        String description
) {
    public ParameterDescriptor {
        java.util.Objects.requireNonNull(name);
        java.util.Objects.requireNonNull(type);
        description = description == null ? "" : description;
        if (variadic) {
            if (required || hasDefault) throw new IllegalArgumentException("Variadic parameter cannot be required or have a default: " + name);
        } else if (required == hasDefault) {
            throw new IllegalArgumentException("Parameter must be either required or have a default: " + name);
        }
    }

    public ParameterDescriptor(String name, VeloraType type, boolean required, boolean hasDefault, Object defaultValue) {
        this(name, type, required, hasDefault, defaultValue, false, "");
    }

    public static ParameterDescriptor required(String name, VeloraType type) {
        return required(name, type, "");
    }

    public static ParameterDescriptor required(String name, VeloraType type, String description) {
        return new ParameterDescriptor(name, type, true, false, null, false, description);
    }

    public static ParameterDescriptor optional(String name, VeloraType type, Object defaultValue) {
        return optional(name, type, defaultValue, "");
    }

    public static ParameterDescriptor optional(String name, VeloraType type, Object defaultValue, String description) {
        return new ParameterDescriptor(name, type, false, true, defaultValue, false, description);
    }

    public static ParameterDescriptor variadic(String name, VeloraType type) {
        return variadic(name, type, "");
    }

    public static ParameterDescriptor variadic(String name, VeloraType type, String description) {
        return new ParameterDescriptor(name, type, false, false, null, true, description);
    }
}
