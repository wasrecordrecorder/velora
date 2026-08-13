package io.velora.internal.ir;

import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;

public sealed interface IrValue {

    record IntVal(int value) implements IrValue {
        public VeloraType type() { return VeloraTypes.INT; }
    }
    record LongVal(long value) implements IrValue {
        public VeloraType type() { return VeloraTypes.LONG; }
    }
    record FloatVal(float value) implements IrValue {
        public VeloraType type() { return VeloraTypes.FLOAT; }
    }
    record DoubleVal(double value) implements IrValue {
        public VeloraType type() { return VeloraTypes.DOUBLE; }
    }
    record StringVal(String value) implements IrValue {
        public VeloraType type() { return VeloraTypes.STRING; }
    }
    record BooleanVal(boolean value) implements IrValue {
        public VeloraType type() { return VeloraTypes.BOOLEAN; }
    }
    record DurationVal(long nanos) implements IrValue {
        public VeloraType type() { return VeloraTypes.DURATION; }
    }
    record NullVal() implements IrValue {
        public VeloraType type() { return VeloraTypes.UNIT.nullable(); }
    }

    static IrValue of(int v) { return new IntVal(v); }
    static IrValue of(long v) { return new LongVal(v); }
    static IrValue of(float v) { return new FloatVal(v); }
    static IrValue of(double v) { return new DoubleVal(v); }
    static IrValue of(String v) { return new StringVal(v); }
    static IrValue of(boolean v) { return new BooleanVal(v); }

    VeloraType type();
}
