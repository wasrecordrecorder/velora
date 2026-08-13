package io.velora.api.type;

import java.util.Objects;

/**
 * Immutable set type: Set<T>.
 */
final class SetType implements VeloraType {

    private final VeloraType element;
    private final boolean nullable;

    SetType(VeloraType element) {
        this(element, false);
    }

    private SetType(VeloraType element, boolean nullable) {
        this.element = element;
        this.nullable = nullable;
    }

    public VeloraType element() {
        return element;
    }

    @Override
    public String name() {
        return "Set<" + element.name() + ">" + (nullable ? "?" : "");
    }

    @Override
    public boolean isHashable() {
        return false;
    }

    @Override
    public boolean isNullable() {
        return nullable;
    }

    @Override
    public Class<?> javaClass() {
        return java.util.Set.class;
    }

    @Override
    public VeloraType nullable() {
        return new SetType(element, true);
    }

    @Override
    public VeloraType nonNull() {
        return nullable ? new SetType(element, false) : this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SetType st)) return false;
        return Objects.equals(element, st.element) && nullable == st.nullable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(element, nullable);
    }
}
