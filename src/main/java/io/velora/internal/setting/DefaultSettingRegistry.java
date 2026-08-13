package io.velora.internal.setting;

import io.velora.api.registry.SettingRegistry;
import io.velora.api.setting.SettingKind;
import io.velora.api.setting.SettingKindBuilder;

import java.util.*;
import java.util.function.Consumer;

public final class DefaultSettingRegistry implements SettingRegistry {

    private final Map<String, SettingKind> byName = new LinkedHashMap<>();
    private boolean frozen;

    @Override
    public void register(SettingKind kind) {
        checkFrozen();
        if (byName.containsKey(kind.name())) {
            return;
        }
        byName.put(kind.name(), kind);
    }

    @Override
    public void register(String name, Consumer<SettingKindBuilder> config) {
        SettingKindBuilder b = SettingKindBuilder.named(name);
        config.accept(b);
        register(b.build());
    }

    @Override
    public SettingKind find(String name) {
        return byName.get(name);
    }

    @Override
    public List<SettingKind> all() {
        return List.copyOf(byName.values());
    }

    @Override
    public Collection<String> names() {
        return Collections.unmodifiableSet(byName.keySet());
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    public void freeze() {
        frozen = true;
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("SettingRegistry is frozen");
    }
}
