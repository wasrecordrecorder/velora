package io.velora.api.type;

import java.util.*;
import java.util.function.Function;

/**
 * Builder for struct types.
 */
public final class StructTypeBuilder {

    private final String name;
    private final Class<?> javaClass;
    private final List<StructType.Property> properties = new ArrayList<>();
    private boolean valueEquality = false;

    public StructTypeBuilder(String name, Class<?> javaClass) {
        this.name = Objects.requireNonNull(name);
        this.javaClass = Objects.requireNonNull(javaClass);
    }

    public StructTypeBuilder property(String name, VeloraType type, Function<Object, Object> accessor) {
        properties.add(new StructType.Property(name, type, accessor));
        return this;
    }

    public StructTypeBuilder valueEquality(boolean valueEquality) {
        this.valueEquality = valueEquality;
        return this;
    }

    public StructType build() {
        return new StructType(name, javaClass, properties, valueEquality, false);
    }
}
