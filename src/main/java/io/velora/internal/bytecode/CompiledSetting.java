package io.velora.internal.bytecode;

import io.velora.api.setting.SettingDescriptor;

public record CompiledSetting(
        int index,
        String id,
        String displayName,
        String typeId,
        Object defaultValue,
        boolean restartRequired,
        boolean secret
) {
    public static CompiledSetting from(SettingDescriptor desc, int index) {
        return new CompiledSetting(
                index, desc.id(), desc.displayName(),
                desc.type() != null ? desc.type().name() : "Unit",
                desc.defaultValue(), desc.restartRequired(), desc.secret()
        );
    }
}
