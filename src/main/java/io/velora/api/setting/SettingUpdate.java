package io.velora.api.setting;

import java.util.Objects;

/**
 * A request to update a setting value (from GUI or transaction).
 */
public record SettingUpdate(String id, SettingValue value) {
    public SettingUpdate {
        Objects.requireNonNull(id);
        Objects.requireNonNull(value);
    }

    public static SettingUpdate of(String id, SettingValue value) {
        return new SettingUpdate(id, value);
    }
}
