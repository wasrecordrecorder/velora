package io.velora.internal.ir;

import io.velora.api.type.VeloraType;

import java.util.List;

public final class IrFunction {
    private final String name;
    private final int index;
    private final List<IrParam> parameters;
    private final VeloraType returnType;
    private final boolean suspending;
    private final boolean isLifecycle;
    private final List<IrBlock> blocks;
    private final int localCount;
    private final int maxStack;

    public IrFunction(String name, int index, List<IrParam> parameters, VeloraType returnType,
                      boolean suspending, boolean isLifecycle, List<IrBlock> blocks,
                      int localCount, int maxStack) {
        this.name = name;
        this.index = index;
        this.parameters = List.copyOf(parameters);
        this.returnType = returnType;
        this.suspending = suspending;
        this.isLifecycle = isLifecycle;
        this.blocks = List.copyOf(blocks);
        this.localCount = localCount;
        this.maxStack = maxStack;
    }

    public String name() { return name; }
    public int index() { return index; }
    public List<IrParam> parameters() { return parameters; }
    public VeloraType returnType() { return returnType; }
    public boolean suspending() { return suspending; }
    public boolean isLifecycle() { return isLifecycle; }
    public List<IrBlock> blocks() { return blocks; }
    public int localCount() { return localCount; }
    public int maxStack() { return maxStack; }

    public record IrParam(String name, VeloraType type) {}
}
