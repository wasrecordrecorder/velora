package io.velora.internal.vm;

import java.util.LinkedHashMap;
import java.util.Map;

public record MapValue(Map<ScriptValue, ScriptValue> entries) implements ScriptValue {
    public MapValue {
        entries = Map.copyOf(entries);
    }
    public boolean isNull() { return false; }
    public Object boxed() {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (var e : entries.entrySet()) {
            result.put(e.getKey().boxed(), e.getValue().boxed());
        }
        return result;
    }
}
