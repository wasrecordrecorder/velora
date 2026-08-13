package io.velora.internal.setting;

import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingKind;

import java.util.*;

public final class SettingDescriptorFactory {

    public static SettingDescriptor create(SettingKind kind, String id, String displayName,
                                            Object defaultValue, String description, String category,
                                            int order, boolean advanced, boolean restartRequired,
                                            boolean secret, String idAlias,
                                            List<SettingDescriptor.Constraint> constraints, int index) {
        return new SettingDescriptor(id, displayName, kind.resolveType(
                new SettingKind.SettingDeclaration(kind.name(), id, List.of(), Map.of())),
                defaultValue, kind.editor().orElse(null), description, category,
                order, advanced, restartRequired, secret, idAlias, constraints, index);
    }
}
