package io.velora.internal.scheduler;

import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.vm.CallFrame;
import io.velora.internal.vm.ScriptValue;
import io.velora.internal.vm.ValueStack;

import java.util.Deque;

public final class ScriptFiber {
    private final long id;
    private final String scriptId;
    private final int functionIndex;
    private final ScriptValue[] args;
    private FiberState state = FiberState.READY;
    private int instructionsThisTick;
    private long sleepUntilNanos;
    private ScriptValue result;
    private Throwable error;
    private long parentId;
    private long awaitTaskId = -1;

    private ValueStack savedStack;
    private Deque<CallFrame> savedCallStack;
    private int savedInstructions;
    private int consecutiveInstructionLimits;

    public ScriptFiber(long id, String scriptId, int functionIndex, ScriptValue[] args) {
        this.id = id;
        this.scriptId = scriptId;
        this.functionIndex = functionIndex;
        this.args = args;
        this.parentId = -1;
    }

    public long id() { return id; }
    public String scriptId() { return scriptId; }
    public int functionIndex() { return functionIndex; }
    public ScriptValue[] args() { return args; }
    public FiberState state() { return state; }
    public void state(FiberState s) { this.state = s; }
    public int instructionsThisTick() { return instructionsThisTick; }
    public void instructionsThisTick(int n) { this.instructionsThisTick = n; }
    public long sleepUntilNanos() { return sleepUntilNanos; }
    public void sleepUntilNanos(long n) { this.sleepUntilNanos = n; }
    public ScriptValue result() { return result; }
    public void result(ScriptValue r) { this.result = r; }
    public Throwable error() { return error; }
    public void error(Throwable e) { this.error = e; }
    public long parentId() { return parentId; }
    public void parentId(long p) { this.parentId = p; }
    public long awaitTaskId() { return awaitTaskId; }
    public void awaitTaskId(long t) { this.awaitTaskId = t; }
    public boolean isDone() { return state == FiberState.COMPLETED || state == FiberState.FAILED || state == FiberState.CANCELLED; }

    public ValueStack savedStack() { return savedStack; }
    public void savedStack(ValueStack s) { this.savedStack = s; }
    public Deque<CallFrame> savedCallStack() { return savedCallStack; }
    public void savedCallStack(Deque<CallFrame> s) { this.savedCallStack = s; }
    public int savedInstructions() { return savedInstructions; }
    public void savedInstructions(int n) { this.savedInstructions = n; }
    public int consecutiveInstructionLimits() { return consecutiveInstructionLimits; }
    public void incrementInstructionLimits() { consecutiveInstructionLimits++; }
    public void resetInstructionLimits() { consecutiveInstructionLimits = 0; }
}
