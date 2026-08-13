package io.velora.internal.setting;

import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SettingStore {
    private final Map<String, SettingCell> cells = new LinkedHashMap<>();
    private final Map<String, SettingDescriptor> descriptorsById = new LinkedHashMap<>();
    private final List<SettingDescriptor> descriptors;

    public SettingStore(List<SettingDescriptor> descriptors) {
        this.descriptors = List.copyOf(descriptors);
        for (SettingDescriptor descriptor : descriptors) {
            descriptorsById.put(descriptor.id(), descriptor);
            SettingValue initial = SettingValidator.normalize(descriptor, SettingValue.of(descriptor.type(), descriptor.defaultValue()));
            cells.put(descriptor.id(), new SettingCell(initial));
        }
    }

    public SettingValue get(String id) {
        SettingCell cell = cells.get(id);
        return cell != null ? cell.value() : null;
    }

    public SettingValue getByIndex(int index) {
        if (index < 0 || index >= descriptors.size()) return null;
        return get(descriptors.get(index).id());
    }

    public void set(String id, SettingValue value) {
        SettingCell cell = cells.get(id);
        SettingDescriptor descriptor = descriptorsById.get(id);
        if (cell == null || descriptor == null) throw new IllegalArgumentException("Unknown setting: " + id);
        cell.value(SettingValidator.normalize(descriptor, value));
    }

    public List<SettingDescriptor> descriptors() { return descriptors; }
    public Map<String, SettingCell> cells() { return Collections.unmodifiableMap(cells); }

    public Map<String, SettingValue> snapshot() {
        Map<String, SettingValue> result = new LinkedHashMap<>();
        for (var entry : cells.entrySet()) result.put(entry.getKey(), entry.getValue().value());
        return result;
    }

    public int applySnapshot(Map<String, SettingValue> snapshot) {
        int applied = 0;
        for (var entry : snapshot.entrySet()) {
            if (!cells.containsKey(entry.getKey())) continue;
            try {
                set(entry.getKey(), entry.getValue());
                applied++;
            } catch (IllegalArgumentException ignored) {}
        }
        return applied;
    }
}
