package io.velora.api.type;

import java.util.*;

/**
 * A struct (record-like) type with named properties.
 */
public final class StructType implements VeloraType {

    private final String name;
    private final Class<?> javaClass;
    private final List<Property> properties;
    private final Map<String, Property> propertyMap;
    private final Map<String, String> propertyDescriptions;
    private final boolean nullable;
    private final boolean valueEquality;

    StructType(String name, Class<?> javaClass, List<Property> properties, boolean valueEquality, boolean nullable) {
        this(name, javaClass, properties, Map.of(), valueEquality, nullable);
    }

    StructType(String name, Class<?> javaClass, List<Property> properties, Map<String, String> propertyDescriptions, boolean valueEquality, boolean nullable) {
        this.name = name;
        this.javaClass = javaClass;
        this.properties = List.copyOf(properties);
        this.propertyMap = new LinkedHashMap<>();
        for (Property p : this.properties) {
            propertyMap.put(p.name(), p);
        }
        this.propertyDescriptions = Map.copyOf(propertyDescriptions);
        this.valueEquality = valueEquality;
        this.nullable = nullable;
    }

    public List<Property> properties() {
        return properties;
    }

    public Property property(String name) {
        return propertyMap.get(name);
    }

    public boolean hasProperty(String name) {
        return propertyMap.containsKey(name);
    }

    public String propertyDescription(String name) {
        return propertyDescriptions.getOrDefault(name, "");
    }

    public boolean valueEquality() {
        return valueEquality;
    }

    @Override
    public String name() {
        return name + (nullable ? "?" : "");
    }

    @Override
    public boolean isNullable() {
        return nullable;
    }

    @Override
    public boolean isHashable() {
        return valueEquality;
    }

    @Override
    public Class<?> javaClass() {
        return javaClass;
    }

    @Override
    public VeloraType nullable() {
        return new StructType(name, javaClass, properties, propertyDescriptions, valueEquality, true);
    }

    @Override
    public VeloraType nonNull() {
        return nullable ? new StructType(name, javaClass, properties, propertyDescriptions, valueEquality, false) : this;
    }

    /**
     * A property of a struct type.
     */
    public record Property(String name, VeloraType type, java.util.function.Function<Object, Object> accessor) {
        public Property {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
        }
    }
}
