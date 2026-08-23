package io.velora.api.data;

import io.velora.api.memory.MemoryFootprint;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class ByteArray implements MemoryFootprint {
    private static final ByteArray EMPTY = new ByteArray(new byte[0], false);
    private final byte[] data;

    private ByteArray(byte[] data, boolean copy) {
        this.data = copy ? data.clone() : data;
    }

    public static ByteArray empty() {
        return EMPTY;
    }

    public static ByteArray copyOf(byte[] data) {
        Objects.requireNonNull(data, "data");
        return data.length == 0 ? EMPTY : new ByteArray(data, true);
    }

    public static ByteArray utf8(String text) {
        Objects.requireNonNull(text, "text");
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return data.length == 0 ? EMPTY : new ByteArray(data, false);
    }

    public int size() {
        return data.length;
    }

    public boolean isEmpty() {
        return data.length == 0;
    }

    public int get(int index) {
        return Byte.toUnsignedInt(data[index]);
    }

    public ByteArray slice(int from, int to) {
        if (from < 0 || to < from || to > data.length) throw new IndexOutOfBoundsException("Invalid byte range: " + from + ".." + to);
        return from == to ? EMPTY : new ByteArray(Arrays.copyOfRange(data, from, to), false);
    }

    public ByteArray concat(ByteArray other) {
        Objects.requireNonNull(other, "other");
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;
        byte[] joined = Arrays.copyOf(data, Math.addExact(data.length, other.data.length));
        System.arraycopy(other.data, 0, joined, data.length, other.data.length);
        return new ByteArray(joined, false);
    }

    public String utf8() {
        return new String(data, StandardCharsets.UTF_8);
    }

    public byte[] toArray() {
        return data.clone();
    }

    @Override
    public long estimatedBytes() {
        return data.length;
    }

    @Override
    public boolean equals(Object value) {
        return this == value || value instanceof ByteArray other && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "ByteArray(" + data.length + ")";
    }
}
