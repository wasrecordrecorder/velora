package io.velora.internal.foreign;

public record ForeignSettingDef(
        String id,
        String name,
        String type,
        Object defaultValue,
        Double min,
        Double max,
        Double step,
        String[] options
) {
}
