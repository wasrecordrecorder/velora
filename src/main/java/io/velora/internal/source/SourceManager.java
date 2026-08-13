package io.velora.internal.source;

import java.util.HashMap;
import java.util.Map;

public final class SourceManager {

    private final Map<String, SourceUnit> units = new HashMap<>();

    public void addSource(SourceUnit unit) {
        String key = unit.scriptId() + ":" + unit.relativePath();
        units.put(key, unit);
    }

    public SourceUnit getSource(String scriptId, String relativePath) {
        return units.get(scriptId + ":" + relativePath);
    }

    public java.util.Collection<SourceUnit> sourcesFor(String scriptId) {
        java.util.List<SourceUnit> result = new java.util.ArrayList<>();
        for (SourceUnit unit : units.values()) {
            if (unit.scriptId().equals(scriptId)) {
                result.add(unit);
            }
        }
        return result;
    }

    public void clear(String scriptId) {
        units.entrySet().removeIf(e -> e.getKey().startsWith(scriptId + ":"));
    }

    public void clearAll() {
        units.clear();
    }

    public int sourceCount() {
        return units.size();
    }
}
