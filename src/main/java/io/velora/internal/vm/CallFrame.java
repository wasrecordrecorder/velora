package io.velora.internal.vm;

import io.velora.internal.bytecode.CompiledFunction;

public final class CallFrame {
    final CompiledFunction function;
    int ip;
    final int stackBase;
    final int localBase;
    public final ScriptValue[] locals;

    public CallFrame(CompiledFunction function, int stackBase, int localBase) {
        this.function = function;
        this.ip = 0;
        this.stackBase = stackBase;
        this.localBase = localBase;
        this.locals = new ScriptValue[Math.max(function.localCount(), function.parameterCount())];
    }

    public CompiledFunction function() { return function; }
    public int ip() { return ip; }
    public int stackBase() { return stackBase; }
    public int localBase() { return localBase; }
    public ScriptValue[] locals() { return locals; }
}
