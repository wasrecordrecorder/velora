package io.velora.api.type;

/**
 * Built-in Velora types available to all scripts.
 */
public final class VeloraTypes {

    private VeloraTypes() {}

    // --- Primitive types ---

    public static final VeloraType UNIT    = SimpleType.of("Unit", void.class, false, true);
    public static final VeloraType NOTHING = SimpleType.of("Nothing", Void.class, false, true);
    public static final VeloraType BOOLEAN = SimpleType.of("Boolean", boolean.class, true, true);
    public static final VeloraType BYTE    = SimpleType.of("Byte", byte.class, true, true);
    public static final VeloraType INT     = SimpleType.of("Int", int.class, true, true);
    public static final VeloraType LONG    = SimpleType.of("Long", long.class, true, true);
    public static final VeloraType FLOAT   = SimpleType.of("Float", float.class, true, false);
    public static final VeloraType DOUBLE  = SimpleType.of("Double", double.class, true, false);
    public static final VeloraType CHAR    = SimpleType.of("Char", char.class, true, true);
    public static final VeloraType STRING  = SimpleType.of("String", String.class, false, true);

    // --- Value types ---

    public static final VeloraType DURATION = SimpleType.of("Duration", java.time.Duration.class, false, true);
    public static final VeloraType VEC2     = SimpleType.of("Vec2", double[].class, false, false);
    public static final VeloraType VEC3     = SimpleType.of("Vec3", double[].class, false, false);
    public static final VeloraType BLOCK_POS = SimpleType.of("BlockPos", int[].class, false, true);
    public static final VeloraType ROTATION = SimpleType.of("Rotation", float[].class, false, false);
    public static final VeloraType COLOR    = SimpleType.of("Color", int[].class, false, false);
    public static final VeloraType KEY      = SimpleType.of("Key", int.class, true, true);
    public static final VeloraType UUID     = SimpleType.of("UUID", java.util.UUID.class, false, true);
    public static final VeloraType IDENTIFIER = SimpleType.of("Identifier", String.class, false, true);

    // --- Minecraft value types ---

    public static final VeloraType BLOCK_ID     = SimpleType.of("BlockId", String.class, false, true);
    public static final VeloraType ITEM_ID      = SimpleType.of("ItemId", String.class, false, true);
    public static final VeloraType ENTITY_TYPE_ID = SimpleType.of("EntityTypeId", String.class, false, true);

    // --- Collection types (immutable in V1) ---

    public static VeloraType list(VeloraType element) {
        return new ListType(element);
    }

    public static VeloraType map(VeloraType key, VeloraType value) {
        return new MapType(key, value);
    }

    public static VeloraType set(VeloraType element) {
        return new SetType(element);
    }

    /**
     * Check if {@code source} can be widened to {@code target} without explicit cast.
     */
    public static boolean isWidening(VeloraType source, VeloraType target) {
        if (source == target) return true;
        if (source == INT && target == LONG) return true;
        if (source == INT && target == DOUBLE) return true;
        if (source == FLOAT && target == DOUBLE) return true;
        if (source == BYTE && target == INT) return true;
        if (source == BYTE && target == LONG) return true;
        if (source == BYTE && target == DOUBLE) return true;
        return false;
    }

    /**
     * Check if two types are compatible (widening or equal).
     */
    public static boolean isCompatible(VeloraType source, VeloraType target) {
        if (source == target) return true;
        if (isWidening(source, target)) return true;
        // Nullable compatibility: non-null is compatible with nullable
        if (target.isNullable() && source.nonNull() == target.nonNull()) return true;
        return false;
    }
}
