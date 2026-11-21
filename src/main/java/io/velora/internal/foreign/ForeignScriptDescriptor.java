package io.velora.internal.foreign;

import java.util.List;

public record ForeignScriptDescriptor(
        String name,
        String version,
        String author,
        String description,
        List<ForeignSettingDef> settings,
        List<String> lifecycleHooks,
        List<String> eventNames
) {
    public ForeignScriptDescriptor {
        settings = settings == null ? List.of() : List.copyOf(settings);
        lifecycleHooks = lifecycleHooks == null ? List.of() : List.copyOf(lifecycleHooks);
        eventNames = eventNames == null ? List.of() : List.copyOf(eventNames);
    }
}
