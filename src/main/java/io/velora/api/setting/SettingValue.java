package io.velora.api.setting;

import io.velora.api.type.VeloraType;

import java.util.Objects;

/**
 * A typed setting value. The carrier type is validated against the descriptor.
 */
public record SettingValue(VeloraType type, Object value) {

    public SettingValue {
        Objects.requireNonNull(type);
    }

    public static SettingValue of(VeloraType type, Object value) {
        return new SettingValue(type, value);
    }

    public static SettingValue ofInt(int v) {
        return new SettingValue(io.velora.api.type.VeloraTypes.INT, v);
    }

    public static SettingValue ofLong(long v) {
        return new SettingValue(io.velora.api.type.VeloraTypes.LONG, v);
    }

    public static SettingValue ofDouble(double v) {
        return new SettingValue(io.velora.api.type.VeloraTypes.DOUBLE, v);
    }

    public static SettingValue ofBoolean(boolean v) {
        return new SettingValue(io.velora.api.type.VeloraTypes.BOOLEAN, v);
    }

    public static SettingValue ofString(String v) {
        return new SettingValue(io.velora.api.type.VeloraTypes.STRING, v);
    }

    public int asInt() {
        return ((Number) value).intValue();
    }

    public long asLong() {
        return ((Number) value).longValue();
    }

    public double asDouble() {
        return ((Number) value).doubleValue();
    }

    public boolean asBoolean() {
        return (Boolean) value;
    }

    public String asString() {
        return (String) value;
    }
}
