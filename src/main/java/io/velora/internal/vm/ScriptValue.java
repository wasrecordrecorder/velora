package io.velora.internal.vm;

public sealed interface ScriptValue permits PrimitiveValue, StringValue, ListValue, MapValue, StructValue, EnumValue, HandleValue, TaskValue {
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
        return new HandleValue("Object", obj);
    }
}
