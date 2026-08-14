package io.velora.api.type;

import java.util.*;

/**
 * An enum type with named constants.
 */
public final class EnumType implements VeloraType {

    private final String name;
    private final Class<?> javaClass;
    private final List<Constant> constants;
    private final Map<String, Constant> constantMap;
    private final boolean nullable;

    public EnumType(String name, Class<?> javaClass, List<Constant> constants) {
        this(name, javaClass, constants, false);
    }

    private EnumType(String name, Class<?> javaClass, List<Constant> constants, boolean nullable) {
        this.name = Objects.requireNonNull(name, "name");
        this.javaClass = Objects.requireNonNull(javaClass, "javaClass");
        Objects.requireNonNull(constants, "constants");
        if (!isIdentifier(name)) throw new IllegalArgumentException("Enum type name must be a script identifier: " + name);
        this.constants = List.copyOf(constants);
        this.constantMap = new LinkedHashMap<>();
        for (Constant constant : this.constants) {
            Objects.requireNonNull(constant, "constant");
            if (!isIdentifier(constant.name())) throw new IllegalArgumentException("Enum constant name must be a script identifier: " + constant.name());
            if (constantMap.putIfAbsent(constant.name(), constant) != null) throw new IllegalArgumentException("Duplicate enum constant: " + constant.name());
            if (constant.value() != null && !javaClass.isInstance(constant.value())) throw new IllegalArgumentException("Enum constant '" + constant.name() + "' must be " + javaClass.getTypeName());
        }
        this.nullable = nullable;
    }

    public List<Constant> constants() {
        return constants;
    }

    public Constant constant(String name) {
        return constantMap.get(name);
    }

    public boolean hasConstant(String name) {
        return constantMap.containsKey(name);
    }

    @Override
    public String name() {
        return name + (nullable ? "?" : "");
    }

    @Override
    public boolean isHashable() {
        return true;
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
        return new EnumType(name, javaClass, constants, true);
    }

    @Override
    public VeloraType nonNull() {
        return nullable ? new EnumType(name, javaClass, constants, false) : this;
    }

    /**
     * An enum constant.
     */
    private static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || !(Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')) return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    public record Constant(String name, Object value) {
        public Constant {
            Objects.requireNonNull(name);
        }
    }
}
