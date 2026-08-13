package io.velora.internal.setting;

import io.velora.api.setting.SettingValue;

public final class SettingCell {
    private SettingValue value;
    private SettingValue pendingValue;
    private boolean dirty;

    public SettingCell(SettingValue initialValue) {
        this.value = initialValue;
    }

    public SettingValue value() { return value; }
    public void value(SettingValue v) { this.value = v; this.dirty = true; }
    public SettingValue pendingValue() { return pendingValue; }
    public void pendingValue(SettingValue v) { this.pendingValue = v; }
    public boolean dirty() { return dirty; }
    public void clearDirty() { dirty = false; }
}
