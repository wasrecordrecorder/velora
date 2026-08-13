package io.velora.api.type;

final class SimpleType implements VeloraType {
    private final String name;
    private final Class<?> javaClass;
    private final boolean primitive;
    private final boolean hashable;
    private final boolean nullable;
    private final SimpleType nonNullVariant;
    private final SimpleType nullableVariant;

    private SimpleType(String name, Class<?> javaClass, boolean primitive, boolean hashable, boolean nullable, SimpleType base) {
        this.name = name;
        this.javaClass = javaClass;
        this.primitive = primitive;
        this.hashable = hashable;
        this.nullable = nullable;
        this.nonNullVariant = base == null ? this : base;
        this.nullableVariant = nullable ? this : new SimpleType(name + "?", javaClass, primitive, hashable, true, this);
    }

    static SimpleType of(String name, Class<?> javaClass, boolean primitive, boolean hashable) {
        return new SimpleType(name, javaClass, primitive, hashable, false, null);
    }

    @Override public String name() { return name; }
    @Override public boolean isPrimitive() { return primitive; }
    @Override public boolean isNullable() { return nullable; }
    @Override public boolean isHashable() { return hashable; }
    @Override public Class<?> javaClass() { return javaClass; }
    @Override public VeloraType nullable() { return nullableVariant; }
    @Override public VeloraType nonNull() { return nonNullVariant; }
    @Override public String toString() { return name; }
}
