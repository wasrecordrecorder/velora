package io.velora.api.registry;

import io.velora.api.setting.SettingKind;
import io.velora.api.setting.SettingKindBuilder;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Registry for setting kinds (the annotation-level types such as Slider, Toggle, ...).
 */
public interface SettingRegistry {

    /** Register a setting kind. Must be called before freeze. */
    void register(SettingKind kind);

    /** Build and register a setting kind. */
    default void register(String name, Consumer<SettingKindBuilder> config) {
        SettingKindBuilder b = SettingKindBuilder.named(name);
        config.accept(b);
        register(b.build());
    }

    /** Find a setting kind by annotation name. */
    SettingKind find(String name);

    /** All registered setting kinds. */
    List<SettingKind> all();

    /** All registered setting kind names. */
    Collection<String> names();

    boolean isFrozen();
}
