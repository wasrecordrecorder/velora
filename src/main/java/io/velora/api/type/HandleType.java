package io.velora.api.type;

/**
 * A handle type referencing a live host object.
 * Handles store host object id, optional UUID, world epoch and generation.
 */
public final class HandleType implements VeloraType {

    private final String name;
    private final Class<?> javaClass;
    private final boolean nullable;

    public HandleType(String name, Class<?> javaClass) {
        this(name, javaClass, false);
    }

    private HandleType(String name, Class<?> javaClass, boolean nullable) {
        this.name = name;
        this.javaClass = javaClass;
        this.nullable = nullable;
    }

    @Override
    public String name() {
        return name + (nullable ? "?" : "");
    }

    @Override
    public boolean isHandle() {
        return true;
    }

    @Override
    public boolean isHashable() {
        return false; // Handles are not hashable as map keys
    }

    @Override
    public boolean isNullable() {
        return nullable;
    }

    @Override
    public Class<?> javaClass() {
        return javaClass;
    }

    @Override
    public VeloraType nullable() {
        return new HandleType(name, javaClass, true);
    }

    @Override
    public VeloraType nonNull() {
        return nullable ? new HandleType(name, javaClass, false) : this;
    }
}
