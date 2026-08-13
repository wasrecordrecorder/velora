package io.velora.internal.bytecode;

/**
 * A compiled function: opcode array plus metadata.
 */
public final class CompiledFunction {

    private final String name;
    private final int index;
    private final int parameterCount;
    private final int localCount;
    private final int maxStack;
    private final boolean suspending;
    private final boolean isLifecycle;
    private final int[] code;
    private final int[] lineNumbers; // parallel to instruction start offsets; -1 if none

    public CompiledFunction(String name, int index, int parameterCount, int localCount, int maxStack,
                            boolean suspending, boolean isLifecycle, int[] code, int[] lineNumbers) {
        this.name = name;
        this.index = index;
        this.parameterCount = parameterCount;
        this.localCount = localCount;
        this.maxStack = maxStack;
        this.suspending = suspending;
        this.isLifecycle = isLifecycle;
        this.code = code;
        this.lineNumbers = lineNumbers;
    }

    public String name() { return name; }
    public int index() { return index; }
    public int parameterCount() { return parameterCount; }
    public int localCount() { return localCount; }
    public int maxStack() { return maxStack; }
    public boolean suspending() { return suspending; }
    public boolean isLifecycle() { return isLifecycle; }
    public int[] code() { return code; }
    public int[] lineNumbers() { return lineNumbers; }
}
