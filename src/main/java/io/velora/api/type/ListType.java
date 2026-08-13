package io.velora.api.type;

import java.util.Objects;

/**
 * Immutable list type: List<T>.
 */
final class ListType implements VeloraType {

    private final VeloraType element;
    private final boolean nullable;

    ListType(VeloraType element) {
        this(element, false);
    }

    private ListType(VeloraType element, boolean nullable) {
        this.element = element;
        this.nullable = nullable;
    }

    public VeloraType element() {
        return element;
    }

    @Override
    public String name() {
        return "List<" + element.name() + ">" + (nullable ? "?" : "");
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
        return java.util.List.class;
    }

    @Override
    public VeloraType nullable() {
        return new ListType(element, true);
    }

    @Override
    public VeloraType nonNull() {
        return nullable ? new ListType(element, false) : this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ListType lt)) return false;
        return Objects.equals(element, lt.element) && nullable == lt.nullable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(element, nullable);
    }
}
