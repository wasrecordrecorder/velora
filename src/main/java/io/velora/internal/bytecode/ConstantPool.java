package io.velora.internal.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * Constant pool for a compiled module. Stores literals referenced by CONST
 * opcodes. Constants are typed: INT, LONG, FLOAT, DOUBLE, STRING, BOOLEAN,
 * DURATION, NULL.
 */
public final class ConstantPool {

    public enum Tag { INT, LONG, FLOAT, DOUBLE, STRING, BOOLEAN, DURATION, NULL }

    private final List<Tag> tags = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();

    public int addInt(int v) {
        return add(Tag.INT, v);
    }

    public int addLong(long v) {
        return add(Tag.LONG, v);
    }

    public int addFloat(float v) {
        return add(Tag.FLOAT, v);
    }

    public int addDouble(double v) {
        return add(Tag.DOUBLE, v);
    }

    public int addString(String v) {
        return add(Tag.STRING, v);
    }

    public int addBoolean(boolean v) {
        return add(Tag.BOOLEAN, v);
    }

    public int addDuration(long nanos) {
        return add(Tag.DURATION, nanos);
    }

    public int addNull() {
        return add(Tag.NULL, null);
    }

    private int add(Tag tag, Object value) {
        // dedup small constants
        for (int i = 0; i < values.size(); i++) {
            if (tags.get(i) == tag && java.util.Objects.equals(values.get(i), value)) {
                return i;
            }
        }
        tags.add(tag);
        values.add(value);
        return values.size() - 1;
    }

    public Tag tag(int index) {
        return tags.get(index);
    }

    public Object value(int index) {
        return values.get(index);
    }

    public int intValue(int index) {
        return (int) values.get(index);
    }

    public long longValue(int index) {
        return (long) values.get(index);
    }

    public float floatValue(int index) {
        return (float) values.get(index);
    }

    public double doubleValue(int index) {
        return (double) values.get(index);
    }

    public String stringValue(int index) {
        return (String) values.get(index);
    }

    public boolean booleanValue(int index) {
        return (boolean) values.get(index);
    }

    public long durationNanos(int index) {
        return (long) values.get(index);
    }

    public int size() {
        return values.size();
    }

    public List<Tag> tags() {
        return List.copyOf(tags);
    }

    public List<Object> values() {
        return List.copyOf(values);
    }
}
