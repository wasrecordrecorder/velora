package io.velora.api.type;

import java.util.Objects;

/**
 * Immutable map type: Map<K, V>.
 */
final class MapType implements VeloraType {

    private final VeloraType key;
    private final VeloraType value;
    private final boolean nullable;

    MapType(VeloraType key, VeloraType value) {
        this(key, value, false);
    }

    private MapType(VeloraType key, VeloraType value, boolean nullable) {
        this.key = key;
        this.value = value;
        this.nullable = nullable;
    }

    public VeloraType key() {
        return key;
    }

    public VeloraType value() {
        return value;
    }

    @Override
    public String name() {
        return "Map<" + key.name() + ", " + value.name() + ">";
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
        return java.util.Map.class;
    }

    @Override
    public VeloraType nullable() {
        return new MapType(key, value, true);
    }

    @Override
    public VeloraType nonNull() {
        return nullable ? new MapType(key, value, false) : this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MapType mt)) return false;
        return Objects.equals(key, mt.key) && Objects.equals(value, mt.value) && nullable == mt.nullable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value, nullable);
    }
}
