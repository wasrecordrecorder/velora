package io.velora.api.type;

public final class VeloraTypes {
    private VeloraTypes() {}

    public static final VeloraType UNIT = SimpleType.of("Unit", void.class, false, true);
    public static final VeloraType NOTHING = SimpleType.of("Nothing", Void.class, false, true);
    public static final VeloraType BOOLEAN = SimpleType.of("Boolean", boolean.class, true, true);
    public static final VeloraType BYTE = SimpleType.of("Byte", byte.class, true, true);
    public static final VeloraType INT = SimpleType.of("Int", int.class, true, true);
    public static final VeloraType LONG = SimpleType.of("Long", long.class, true, true);
    public static final VeloraType FLOAT = SimpleType.of("Float", float.class, true, false);
    public static final VeloraType DOUBLE = SimpleType.of("Double", double.class, true, false);
    public static final VeloraType CHAR = SimpleType.of("Char", char.class, true, true);
    public static final VeloraType STRING = SimpleType.of("String", String.class, false, true);
    public static final VeloraType DURATION = SimpleType.of("Duration", java.time.Duration.class, false, true);
    public static final VeloraType VEC2 = SimpleType.of("Vec2", double[].class, false, false);
    public static final VeloraType VEC3 = SimpleType.of("Vec3", double[].class, false, false);
    public static final VeloraType COLOR = SimpleType.of("Color", int[].class, false, false);
    public static final VeloraType UUID = SimpleType.of("UUID", java.util.UUID.class, false, true);

    public static VeloraType list(VeloraType element) { return new ListType(element); }
    public static VeloraType map(VeloraType key, VeloraType value) { return new MapType(key, value); }
    public static VeloraType set(VeloraType element) { return new SetType(element); }
    public static VeloraType task(VeloraType result) { return new TaskType(result); }

    public static VeloraType listElement(VeloraType type) {
        return type instanceof ListType list ? list.element() : null;
    }

    public static VeloraType mapKey(VeloraType type) {
        return type instanceof MapType map ? map.key() : null;
    }

    public static VeloraType mapValue(VeloraType type) {
        return type instanceof MapType map ? map.value() : null;
    }

    public static VeloraType setElement(VeloraType type) {
        return type instanceof SetType set ? set.element() : null;
    }

    public static VeloraType taskResult(VeloraType type) {
        return type instanceof TaskType task ? task.result() : null;
    }

    public static boolean isWidening(VeloraType source, VeloraType target) {
        if (source == target) return true;
        if (source == BYTE) return target == INT || target == LONG || target == FLOAT || target == DOUBLE;
        if (source == INT) return target == LONG || target == FLOAT || target == DOUBLE;
        if (source == LONG) return target == DOUBLE;
        return source == FLOAT && target == DOUBLE;
    }

    public static boolean isCompatible(VeloraType source, VeloraType target) {
        if (source == null || target == null) return false;
        if (source == target || isWidening(source, target)) return true;
        return target.isNullable() && source.nonNull().name().equals(target.nonNull().name());
    }
}
