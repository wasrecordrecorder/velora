package io.velora.api.type;

public final class VeloraTypes {
    private VeloraTypes() {}

    public static final VeloraType ANY = SimpleType.of("Any", Object.class, false, false);
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
        if (source == null || target == null || source.isNullable() && !target.isNullable()) return false;
        VeloraType from = source.nonNull();
        VeloraType to = target.nonNull();
        if (to == ANY) return true;
        if (from == ANY) return to == ANY;
        return sameType(from, to) || isWidening(from, to);
    }

    private static boolean sameType(VeloraType left, VeloraType right) {
        if (left == right || left.equals(right)) return true;
        if (left instanceof ListType a && right instanceof ListType b) return sameType(a.element(), b.element());
        if (left instanceof SetType a && right instanceof SetType b) return sameType(a.element(), b.element());
        if (left instanceof MapType a && right instanceof MapType b) return sameType(a.key(), b.key()) && sameType(a.value(), b.value());
        if (left instanceof TaskType a && right instanceof TaskType b) return sameType(a.result(), b.result());
        if (left instanceof StructType a && right instanceof StructType b) {
            if (!a.name().equals(b.name()) || a.javaClass() != b.javaClass() || a.properties().size() != b.properties().size()) return false;
            for (int i = 0; i < a.properties().size(); i++) {
                StructType.Property x = a.properties().get(i);
                StructType.Property y = b.properties().get(i);
                if (!x.name().equals(y.name()) || !sameType(x.type(), y.type())) return false;
            }
            return true;
        }
        if (left instanceof EnumType a && right instanceof EnumType b) {
            if (!a.name().equals(b.name()) || a.javaClass() != b.javaClass() || a.constants().size() != b.constants().size()) return false;
            for (int i = 0; i < a.constants().size(); i++) if (!a.constants().get(i).name().equals(b.constants().get(i).name())) return false;
            return true;
        }
        return left instanceof HandleType && right instanceof HandleType
                && left.name().equals(right.name()) && left.javaClass() == right.javaClass();
    }
}
