package io.velora.internal.vm;

import java.util.LinkedHashMap;
import java.util.Map;

public record MapValue(Map<ScriptValue, ScriptValue> entries) implements ScriptValue {
    public MapValue { entries = new LinkedHashMap<>(entries); }
    @Override public boolean isNull() { return false; }
    @Override public Object boxed() {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (var entry : entries.entrySet()) result.put(entry.getKey().boxed(), entry.getValue().boxed());
        return result;
    }
}
