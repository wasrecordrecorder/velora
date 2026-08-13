package io.velora.internal.vm;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public sealed interface ScriptValue permits PrimitiveValue, StringValue, ListValue, MapValue, SetValue, StructValue, EnumValue, HandleValue, TaskValue {
    boolean isNull();
    Object boxed();

    static ScriptValue fromJava(Object obj) {
        if (obj == null) return PrimitiveValue.nullValue();
        if (obj instanceof ScriptValue sv) return sv;
        if (obj instanceof String s) return new StringValue(s);
        if (obj instanceof Integer i) return PrimitiveValue.of(i);
        if (obj instanceof Long l) return PrimitiveValue.of(l);
        if (obj instanceof Double d) return PrimitiveValue.of(d);
        if (obj instanceof Float f) return PrimitiveValue.of(f);
        if (obj instanceof Boolean b) return PrimitiveValue.of(b);
        if (obj instanceof Byte b) return PrimitiveValue.of(b);
        if (obj instanceof Short s) return PrimitiveValue.of((int) s);
        if (obj instanceof Character c) return PrimitiveValue.of(c);
        if (obj instanceof List<?> list) {
            List<ScriptValue> values = new ArrayList<>(list.size());
            for (Object value : list) values.add(fromJava(value));
            return new ListValue(values);
        }
        if (obj instanceof Map<?, ?> map) {
            Map<ScriptValue, ScriptValue> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) values.put(fromJava(entry.getKey()), fromJava(entry.getValue()));
            return new MapValue(values);
        }
        if (obj instanceof Set<?> set) {
            Set<ScriptValue> values = new java.util.LinkedHashSet<>();
            for (Object value : set) values.add(fromJava(value));
            return new SetValue(values);
        }
        if (obj.getClass().isArray()) {
            int length = Array.getLength(obj);
            List<ScriptValue> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) values.add(fromJava(Array.get(obj, i)));
            return new ListValue(values);
        }
        return new HandleValue(obj.getClass().getName(), obj);
    }
}
