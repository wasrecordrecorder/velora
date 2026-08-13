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
        this.name = name;
        this.javaClass = javaClass;
        this.constants = List.copyOf(constants);
        this.constantMap = new LinkedHashMap<>();
        for (Constant c : this.constants) {
            constantMap.put(c.name(), c);
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
    public record Constant(String name, Object value) {
        public Constant {
            Objects.requireNonNull(name);
        }
    }
}
