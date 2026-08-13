package io.velora.api.type;

/**
 * Represents a type in the Velora type system.
 * Types are immutable after registration and frozen by the engine.
 */
public interface VeloraType {

    /**
     * The fully qualified name of this type, e.g. "Int", "String", "Vec3".
     */
    String name();

    /**
     * Whether this type is a primitive value type.
     */
    default boolean isPrimitive() {
        return false;
    }

    /**
     * Whether this type is nullable in script code.
     */
    default boolean isNullable() {
        return false;
    }

    /**
     * Whether this type can be used as a Map key (stable hashable).
     */
    default boolean isHashable() {
        return false;
    }

    /**
     * Whether this type is a handle to a live host object.
     */
    default boolean isHandle() {
        return false;
    }

    /**
     * The Java class that values of this type map to.
     */
    Class<?> javaClass();

    /**
     * Create a nullable variant of this type.
     */
    VeloraType nullable();

    /**
     * The non-nullable base of this type (or itself if already non-nullable).
     */
    VeloraType nonNull();
}
