package io.velora.internal.vm;

public record HandleValue(String typeName, Object handle) implements ScriptValue {
    public boolean isNull() { return handle == null; }
    public Object boxed() { return handle; }
}
