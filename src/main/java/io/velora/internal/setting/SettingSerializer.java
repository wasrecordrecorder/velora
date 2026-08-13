package io.velora.internal.setting;

import io.velora.api.setting.SettingValue;

import java.util.*;

public final class SettingSerializer {

    public static Map<String, Object> serialize(Map<String, SettingValue> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var e : values.entrySet()) {
            result.put(e.getKey(), e.getValue().value());
        }
        return result;
    }

    public static Map<String, SettingValue> deserialize(Map<String, Object> raw) {
        Map<String, SettingValue> result = new LinkedHashMap<>();
        for (var e : raw.entrySet()) {
            result.put(e.getKey(), SettingValue.of(io.velora.api.type.VeloraTypes.UNIT, e.getValue()));
        }
        return result;
    }
}
