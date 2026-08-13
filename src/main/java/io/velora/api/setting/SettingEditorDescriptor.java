package io.velora.api.setting;

import java.util.Objects;

/**
 * Describes the GUI editor for a setting kind.
 *
 * <p>Velora stores only the editor id; the host GUI registers a widget factory
 * for that id (e.g. {@code velora.slider}, {@code minecraft.block-picker}).
 */
public record SettingEditorDescriptor(String editorId) {
    public SettingEditorDescriptor {
        Objects.requireNonNull(editorId);
    }

    public static SettingEditorDescriptor of(String editorId) {
        return new SettingEditorDescriptor(editorId);
    }
}
