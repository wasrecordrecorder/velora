package io.velora.internal.vm;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public sealed interface ScriptValue permits PrimitiveValue, StringValue, ListValue, MapValue, SetValue, StructValue, EnumValue, HandleValue, TaskValue {
    boolean isNull();
    Object boxed();

    static ScriptValue fromJava(Object obj) {
        return fromJava(obj, new IdentityHashMap<>());
    }

    private static ScriptValue fromJava(Object obj, IdentityHashMap<Object, Boolean> active) {
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
            enter(list, active);
            try {
                List<ScriptValue> values = new ArrayList<>(list.size());
                for (Object value : list) values.add(fromJava(value, active));
                return new ListValue(values);
            } finally { active.remove(list); }
        }
        if (obj instanceof Map<?, ?> map) {
            enter(map, active);
            try {
                Map<ScriptValue, ScriptValue> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) values.put(fromJava(entry.getKey(), active), fromJava(entry.getValue(), active));
                return new MapValue(values);
            } finally { active.remove(map); }
        }
        if (obj instanceof Set<?> set) {
            enter(set, active);
            try {
                Set<ScriptValue> values = new java.util.LinkedHashSet<>();
                for (Object value : set) values.add(fromJava(value, active));
                return new SetValue(values);
            } finally { active.remove(set); }
        }
        if (obj.getClass().isArray()) {
            enter(obj, active);
            try {
                int length = Array.getLength(obj);
                List<ScriptValue> values = new ArrayList<>(length);
                for (int i = 0; i < length; i++) values.add(fromJava(Array.get(obj, i), active));
                return new ListValue(values);
            } finally { active.remove(obj); }
        }
        return new HandleValue(obj.getClass().getName(), obj);
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> active) {
        if (active.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic host values are not supported");
    }
}
