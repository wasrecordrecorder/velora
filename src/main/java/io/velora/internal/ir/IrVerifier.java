package io.velora.internal.ir;

import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.compiler.DiagnosticSeverity;
import io.velora.api.compiler.SourceRange;

import java.util.ArrayList;
import java.util.List;

public final class IrVerifier {

    public List<Diagnostic> verify(IrModule module) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (IrFunction fn : module.functions()) {
            verifyFunction(fn, diagnostics);
        }
        return diagnostics;
    }

    private void verifyFunction(IrFunction fn, List<Diagnostic> diagnostics) {
        for (IrBlock block : fn.blocks()) {
            int stackDepth = 0;
            int maxStack = 0;
            for (IrInstruction instr : block.instructions()) {
                int delta = stackDelta(instr);
                stackDepth += delta;
                if (stackDepth < 0) {
                    diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_STACK_MISMATCH,
                            "Stack underflow in function " + fn.name(), SourceRange.of("ir", 0, 0)));
                    return;
                }
                maxStack = Math.max(maxStack, stackDepth);
                if (instr instanceof IrInstruction.Return) {
                    stackDepth = 0;
                }
                if (instr instanceof IrInstruction.Jump) {
                    stackDepth = 0;
                }
            }
            if (maxStack > fn.maxStack()) {
                diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_STACK_MISMATCH,
                        "Stack exceeds maxStack in function " + fn.name(), SourceRange.of("ir", 0, 0)));
            }
        }
    }

    private int stackDelta(IrInstruction instr) {
        return switch (instr) {
            case IrInstruction.Const v -> 1;
            case IrInstruction.LoadLocal l -> 1;
            case IrInstruction.StoreLocal s -> -1;
            case IrInstruction.LoadField l -> 1;
            case IrInstruction.StoreField s -> -1;
            case IrInstruction.LoadSetting l -> 1;
            case IrInstruction.LoadStatic l -> 1;
            case IrInstruction.StoreStatic s -> -1;
            case IrInstruction.Pop p -> -1;
            case IrInstruction.Dup d -> 1;
            case IrInstruction.BinaryOp b -> -1;
            case IrInstruction.UnaryOp u -> 0;
            case IrInstruction.Compare c -> -1;
            case IrInstruction.Not n -> 0;
            case IrInstruction.IsNull i -> 0;
            case IrInstruction.Jump j -> 0;
            case IrInstruction.JumpIfFalse j -> -1;
            case IrInstruction.JumpIfTrue j -> -1;
            case IrInstruction.Return r -> 0;
            case IrInstruction.Call c -> -(c.argCount()) + (c.returnType() == null ? 0 : 1);
            case IrInstruction.CallApi c -> -(c.argCount()) + 1;
            case IrInstruction.CallSuspend c -> -(c.argCount()) + 1;
            case IrInstruction.GetMember g -> 0;
            case IrInstruction.SetMember s -> -2;
            case IrInstruction.CreateList c -> -(c.elementCount()) + 1;
            case IrInstruction.CreateMap c -> -(c.entryCount() * 2) + 1;
            case IrInstruction.GetIndex g -> -2 + 1;
            case IrInstruction.Spawn s -> -(s.argCount()) + 1;
            case IrInstruction.Await a -> -1 + 1;
            case IrInstruction.Delay d -> -1;
            case IrInstruction.Yield y -> 0;
            case IrInstruction.CheckCancelled c -> 0;
            case IrInstruction.Line l -> 0;
            case IrInstruction.Breakpoint b -> 0;
        };
    }
}
