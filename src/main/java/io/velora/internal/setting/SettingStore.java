package io.velora.internal.setting;

import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingValue;

import java.util.*;

public final class SettingStore {
    private final Map<String, SettingCell> cells = new LinkedHashMap<>();
    private final List<SettingDescriptor> descriptors;

    public SettingStore(List<SettingDescriptor> descriptors) {
        this.descriptors = List.copyOf(descriptors);
        for (SettingDescriptor desc : descriptors) {
            cells.put(desc.id(), new SettingCell(SettingValue.of(desc.type(), desc.defaultValue())));
        }
    }

    public SettingValue get(String id) {
        SettingCell cell = cells.get(id);
        return cell != null ? cell.value() : null;
    }

    public SettingValue getByIndex(int index) {
        if (index < 0 || index >= descriptors.size()) return null;
        SettingCell cell = cells.get(descriptors.get(index).id());
        return cell != null ? cell.value() : null;
    }

    public void set(String id, SettingValue value) {
        SettingCell cell = cells.get(id);
        if (cell != null) cell.value(value);
    }

    public List<SettingDescriptor> descriptors() { return descriptors; }
    public Map<String, SettingCell> cells() { return Collections.unmodifiableMap(cells); }

    public Map<String, SettingValue> snapshot() {
        Map<String, SettingValue> result = new LinkedHashMap<>();
        for (var e : cells.entrySet()) {
            result.put(e.getKey(), e.getValue().value());
        }
        return result;
    }

    public void applySnapshot(Map<String, SettingValue> snapshot) {
        for (var e : snapshot.entrySet()) {
            SettingCell cell = cells.get(e.getKey());
            if (cell != null) cell.value(e.getValue());
        }
    }
}
