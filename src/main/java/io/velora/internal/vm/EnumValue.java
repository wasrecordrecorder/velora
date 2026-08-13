package io.velora.internal.vm;

public record EnumValue(String enumName, String constantName, int ordinal, Object hostValue) implements ScriptValue {
    public EnumValue(String enumName, String constantName, int ordinal) {
        this(enumName, constantName, ordinal, constantName);
    }

    public boolean isNull() { return false; }
    public Object boxed() { return hostValue; }
}
