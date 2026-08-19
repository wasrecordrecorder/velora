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
    private final Map<String, String> propertyDescriptions = new LinkedHashMap<>();
    private boolean valueEquality = false;

    public StructTypeBuilder(String name, Class<?> javaClass) {
        this.name = Objects.requireNonNull(name);
        this.javaClass = Objects.requireNonNull(javaClass);
    }

    public StructTypeBuilder property(String name, VeloraType type, Function<Object, Object> accessor) {
        return property(name, type, "", accessor);
    }

    public StructTypeBuilder property(String name, VeloraType type, String description, Function<Object, Object> accessor) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(accessor, "accessor");
        if (!isIdentifier(name)) throw new IllegalArgumentException("Struct property name must be a script identifier: " + name);
        if (description != null && !description.isEmpty() && description.isBlank()) throw new IllegalArgumentException("Property description cannot be blank: " + name);
        if (properties.stream().anyMatch(property -> property.name().equals(name))) throw new IllegalArgumentException("Duplicate struct property: " + name);
        properties.add(new StructType.Property(name, type, accessor));
        if (description != null && !description.isEmpty()) propertyDescriptions.put(name, description);
        return this;
    }

    public StructTypeBuilder valueEquality(boolean valueEquality) {
        this.valueEquality = valueEquality;
        return this;
    }

    public StructType build() {
        if (!isIdentifier(name)) throw new IllegalArgumentException("Struct type name must be a script identifier: " + name);
        return new StructType(name, javaClass, properties, propertyDescriptions, valueEquality, false);
    }

    private static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || !(Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')) return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }
}
