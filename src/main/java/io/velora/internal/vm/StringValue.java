package io.velora.internal.vm;

public record StringValue(String value) implements ScriptValue {
    public StringValue {
        java.util.Objects.requireNonNull(value);
    }
    public boolean isNull() { return false; }
    public Object boxed() { return value; }
}
