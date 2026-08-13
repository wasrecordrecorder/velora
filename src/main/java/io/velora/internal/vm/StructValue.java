package io.velora.internal.vm;

import java.util.LinkedHashMap;
import java.util.Map;

public record StructValue(String typeName, Map<String, ScriptValue> fields) implements ScriptValue {
    public StructValue {
        fields = Map.copyOf(fields);
    }
    public boolean isNull() { return false; }
    public Object boxed() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var e : fields.entrySet()) {
            result.put(e.getKey(), e.getValue().boxed());
        }
        return result;
    }
}
