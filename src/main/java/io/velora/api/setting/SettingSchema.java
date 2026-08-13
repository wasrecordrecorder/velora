package io.velora.api.setting;

import java.util.List;
import java.util.Optional;

/**
 * Schema of all settings for a compiled script revision.
 */
public record SettingSchema(List<SettingDescriptor> settings) {

    public SettingSchema {
        settings = List.copyOf(settings);
    }

    public static SettingSchema empty() {
        return new SettingSchema(List.of());
    }

    public Optional<SettingDescriptor> find(String id) {
        for (SettingDescriptor d : settings) {
            if (d.id().equals(id) || d.idAlias().map(id::equals).orElse(false)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    public int size() {
        return settings.size();
    }
}
