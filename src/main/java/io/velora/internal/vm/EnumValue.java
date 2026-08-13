package io.velora.internal.vm;

public record EnumValue(String enumName, String constantName, int ordinal) implements ScriptValue {
    public boolean isNull() { return false; }
    public Object boxed() { return constantName; }
}
