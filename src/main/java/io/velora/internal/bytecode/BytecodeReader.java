package io.velora.internal.bytecode;

import java.util.*;

public final class BytecodeReader {

    public record ReadResult(Opcode opcode, int[] operands) {}

    public static ReadResult read(int[] code, int ip) {
        if (ip < 0 || ip >= code.length) return null;
        int ordinal = code[ip];
        Opcode[] opcodes = Opcode.values();
        if (ordinal < 0 || ordinal >= opcodes.length) return null;
        Opcode op = opcodes[ordinal];
        int operandCount = op.operandWords();
        int[] operands = new int[operandCount];
        for (int i = 0; i < operandCount && (ip + 1 + i) < code.length; i++) {
            operands[i] = code[ip + 1 + i];
        }
        return new ReadResult(op, operands);
    }

    public static int instructionSize(Opcode op) {
        return op.instructionWords();
    }
}
