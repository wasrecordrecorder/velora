package io.velora.api.registry;

import io.velora.api.type.VeloraType;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Registry for Velora types (primitives, structs, enums, handles, collections).
 */
public interface TypeRegistry {

    /** Register a struct type. */
    VeloraType struct(String name, Class<?> javaClass, Consumer<io.velora.api.type.StructTypeBuilder> config);

    /** Register an enum type. */
    VeloraType enumType(String name, Class<?> javaClass, List<io.velora.api.type.EnumType.Constant> constants);

    /** Register a handle type. */
    VeloraType handle(String name, Class<?> javaClass);

    /** Register an arbitrary type. */
    void register(VeloraType type);

    /** Find a type by name (non-nullable form). */
    VeloraType find(String name);

    /** Find a type by its assigned index (after freeze). */
    VeloraType findByIndex(int index);

    /** All registered types. */
    List<VeloraType> all();

    /** All registered type names. */
    Collection<String> names();

    boolean isFrozen();
}
