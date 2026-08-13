package io.velora.internal.ir;

import java.util.List;

public final class IrBlock {
    private final int index;
    private final List<IrInstruction> instructions;
    private final List<Integer> predecessors;
    private final List<Integer> successors;

    public IrBlock(int index, List<IrInstruction> instructions,
                   List<Integer> predecessors, List<Integer> successors) {
        this.index = index;
        this.instructions = List.copyOf(instructions);
        this.predecessors = List.copyOf(predecessors);
        this.successors = List.copyOf(successors);
    }

    public int index() { return index; }
    public List<IrInstruction> instructions() { return instructions; }
    public List<Integer> predecessors() { return predecessors; }
    public List<Integer> successors() { return successors; }
}
