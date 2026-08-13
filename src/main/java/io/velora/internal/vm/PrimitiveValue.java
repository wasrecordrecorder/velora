package io.velora.internal.vm;

public sealed interface PrimitiveValue extends ScriptValue permits PrimitiveValue.IntV, PrimitiveValue.LongV,
        PrimitiveValue.FloatV, PrimitiveValue.DoubleV, PrimitiveValue.BooleanV, PrimitiveValue.NullV,
        PrimitiveValue.ByteV, PrimitiveValue.CharV {

    record IntV(int value) implements PrimitiveValue {
        public boolean isNull() { return false; }
        public Object boxed() { return value; }
    }
    record LongV(long value) implements PrimitiveValue {
        public boolean isNull() { return false; }
        public Object boxed() { return value; }
    }
    record FloatV(float value) implements PrimitiveValue {
        public boolean isNull() { return false; }
        public Object boxed() { return value; }
    }
    record DoubleV(double value) implements PrimitiveValue {
        public boolean isNull() { return false; }
        public Object boxed() { return value; }
    }
    record BooleanV(boolean value) implements PrimitiveValue {
        public boolean isNull() { return false; }
        public Object boxed() { return value; }
    }
    record ByteV(byte value) implements PrimitiveValue {
        public boolean isNull() { return false; }
        public Object boxed() { return value; }
    }
    record CharV(char value) implements PrimitiveValue {
        public boolean isNull() { return false; }
        public Object boxed() { return value; }
    }
    record NullV() implements PrimitiveValue {
        public boolean isNull() { return true; }
        public Object boxed() { return null; }
    }

    static PrimitiveValue of(int v) { return new IntV(v); }
    static PrimitiveValue of(long v) { return new LongV(v); }
    static PrimitiveValue of(float v) { return new FloatV(v); }
    static PrimitiveValue of(double v) { return new DoubleV(v); }
    static PrimitiveValue of(boolean v) { return new BooleanV(v); }
    static PrimitiveValue of(byte v) { return new ByteV(v); }
    static PrimitiveValue of(char v) { return new CharV(v); }
    static PrimitiveValue nullValue() { return new NullV(); }
}
