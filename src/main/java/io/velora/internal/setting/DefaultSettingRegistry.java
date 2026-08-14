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
        Objects.requireNonNull(kind, "kind");
        if (byName.containsKey(kind.name())) throw new IllegalStateException("Setting kind already registered: " + kind.name());
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

    public void rollbackTo(int snapshotSize) {
        if (byName.size() <= snapshotSize) return;
        List<String> names = new ArrayList<>(byName.keySet());
        for (int i = names.size() - 1; i >= snapshotSize; i--) byName.remove(names.get(i));
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("SettingRegistry is frozen");
    }
}
