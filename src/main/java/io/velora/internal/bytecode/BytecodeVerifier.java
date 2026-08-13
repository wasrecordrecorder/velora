package io.velora.internal.bytecode;

import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.compiler.DiagnosticSeverity;
import io.velora.api.compiler.SourceRange;

import java.util.*;

public final class BytecodeVerifier {

    public List<Diagnostic> verify(CompiledModule module) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (CompiledFunction fn : module.functions()) {
            verifyFunction(fn, module, diagnostics);
        }
        return diagnostics;
    }

    private void verifyFunction(CompiledFunction fn, CompiledModule module, List<Diagnostic> diagnostics) {
        int[] code = fn.code();
        int ip = 0;
        int stackDepth = 0;
        while (ip < code.length) {
            int ordinal = code[ip];
            Opcode[] opcodes = Opcode.values();
            if (ordinal < 0 || ordinal >= opcodes.length) {
                diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_INVALID_OPCODE,
                        "Invalid opcode " + ordinal + " in " + fn.name(), SourceRange.of("bc", 0, 0)));
                return;
            }
            Opcode op = opcodes[ordinal];
            int size = op.instructionWords();

            if (op.operandWords() > 0) {
                for (int i = 0; i < op.operandWords(); i++) {
                    int operandIndex = ip + 1 + i;
                    if (operandIndex >= code.length) {
                        diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_BAD_OPERAND,
                                "Truncated operand for " + op + " in " + fn.name(), SourceRange.of("bc", 0, 0)));
                        return;
                    }
                }
                if (op == Opcode.JUMP || op == Opcode.JUMP_IF_FALSE || op == Opcode.JUMP_IF_TRUE || op == Opcode.LOOP) {
                    int target = code[ip + 1];
                    if (target < 0 || target > code.length) {
                        diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_BAD_JUMP,
                                "Jump target " + target + " out of range in " + fn.name(), SourceRange.of("bc", 0, 0)));
                    }
                }
                if (op == Opcode.CALL || op == Opcode.SPAWN) {
                    int funcIdx = code[ip + 1];
                    if (funcIdx < 0 || funcIdx >= module.functions().size()) {
                        diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_BAD_OPERAND,
                                "Function index " + funcIdx + " out of range in " + fn.name(), SourceRange.of("bc", 0, 0)));
                    }
                }
                if (op == Opcode.CONST) {
                    int constIdx = code[ip + 1];
                    if (constIdx < 0 || constIdx >= module.constantPool().size()) {
                        diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_BAD_OPERAND,
                                "Constant index " + constIdx + " out of range in " + fn.name(), SourceRange.of("bc", 0, 0)));
                    }
                }
                if (op == Opcode.LOAD_LOCAL) {
                    int localIdx = code[ip + 1];
                    if (localIdx < 0 || localIdx >= fn.localCount()) {
                        diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_BAD_OPERAND,
                                "Local index " + localIdx + " out of range in " + fn.name(), SourceRange.of("bc", 0, 0)));
                    }
                }
                if (op == Opcode.STORE_LOCAL) {
                    int localIdx = code[ip + 1];
                    if (localIdx < 0 || localIdx >= fn.localCount()) {
                        diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_BAD_OPERAND,
                                "Local index " + localIdx + " out of range in " + fn.name(), SourceRange.of("bc", 0, 0)));
                    }
                }
            }

            // Stack depth tracking (linear, basic blocks only)
            switch (op) {
                case CONST, NULL, TRUE, FALSE, LOAD_LOCAL, LOAD_FIELD, LOAD_SETTING, LOAD_STATIC, DUP -> stackDepth++;
                case STORE_LOCAL, STORE_FIELD, STORE_STATIC, POP -> stackDepth--;
                case ADD, SUB, MUL, DIV, MOD, EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, GET_INDEX -> stackDepth--;
                case NEGATE, NOT, IS_NULL, GET_MEMBER -> {}
                case SET_MEMBER -> stackDepth -= 2;
                case RETURN -> stackDepth = Math.max(stackDepth - 1, 0);
                case CALL, CALL_API, CALL_SUSPEND -> {
                    int argCount = (op.operandWords() >= 2 && ip + 2 < code.length) ? code[ip + 2] : 0;
                    stackDepth = Math.max(stackDepth - argCount, 0);
                    stackDepth++;
                }
                case SPAWN -> {
                    int argCount = (op.operandWords() >= 2 && ip + 2 < code.length) ? code[ip + 2] : 0;
                    stackDepth = Math.max(stackDepth - argCount, 0);
                    stackDepth++;
                }
                case AWAIT, DELAY -> stackDepth = Math.max(stackDepth - 1, 0);
                case JUMP_IF_FALSE, JUMP_IF_TRUE -> stackDepth = Math.max(stackDepth - 1, 0);
                case CREATE_LIST -> {
                    int count = (ip + 1 < code.length) ? code[ip + 1] : 0;
                    stackDepth = Math.max(stackDepth - count, 0);
                    stackDepth++;
                }
                case CREATE_MAP -> {
                    int count = (ip + 1 < code.length) ? code[ip + 1] : 0;
                    stackDepth = Math.max(stackDepth - count * 2, 0);
                    stackDepth++;
                }
                default -> {}
            }

            if (stackDepth < 0) {
                diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_STACK_MISMATCH,
                        "Stack underflow in " + fn.name(), SourceRange.of("bc", 0, 0)));
            }

            ip += size;
        }
    }
}
