package io.velora.internal.vm;

import java.util.Arrays;

public final class ValueStack {
    private ScriptValue[] data;
    private int top;

    public ValueStack(int initialCapacity) {
        this.data = new ScriptValue[initialCapacity];
        this.top = 0;
    }

    public void push(ScriptValue value) {
        if (top >= data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[top++] = value;
    }

    public ScriptValue pop() {
        if (top == 0) throw new IllegalStateException("Stack underflow");
        ScriptValue v = data[--top];
        data[top] = null;
        return v;
    }

    public ScriptValue peek() {
        if (top == 0) throw new IllegalStateException("Stack underflow");
        return data[top - 1];
    }

    public ScriptValue peekAt(int depth) {
        return data[top - 1 - depth];
    }

    public void dup() {
        push(peek());
    }

    public void popN(int n) {
        for (int i = 0; i < n; i++) pop();
    }

    public int size() { return top; }
    public boolean isEmpty() { return top == 0; }

    public void clear() {
        Arrays.fill(data, 0, top, null);
        top = 0;
    }

    public void reset(int base) {
        Arrays.fill(data, base, top, null);
        top = base;
    }

    public ScriptValue[] toArray() {
        return Arrays.copyOf(data, top);
    }
}
