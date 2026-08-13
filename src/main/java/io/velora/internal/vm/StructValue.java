package io.velora.internal.vm;

import java.util.LinkedHashMap;
import java.util.Map;

public record StructValue(String typeName, Map<String, ScriptValue> fields, Object hostValue) implements ScriptValue {
    public StructValue(String typeName, Map<String, ScriptValue> fields) {
        this(typeName, fields, null);
    }

    public StructValue {
        fields = Map.copyOf(fields);
    }

    public boolean isNull() { return false; }

    public Object boxed() {
        if (hostValue != null) return hostValue;
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) result.put(entry.getKey(), entry.getValue().boxed());
        return result;
    }
}
