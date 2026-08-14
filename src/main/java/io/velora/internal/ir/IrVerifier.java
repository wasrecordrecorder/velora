package io.velora.internal.ir;

import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.compiler.SourceRange;
import io.velora.api.type.VeloraTypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class IrVerifier {
    private static final SourceRange RANGE = SourceRange.of("ir", 0, 0);

    public List<Diagnostic> verify(IrModule module) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < module.functions().size(); i++) {
            IrFunction function = module.functions().get(i);
            if (function.index() != i) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND,
                    "Function index mismatch for " + function.name() + ": " + function.index() + " != " + i);
            verifyFunction(module, function, diagnostics);
        }
        return diagnostics;
    }

    private void verifyFunction(IrModule module, IrFunction function, List<Diagnostic> diagnostics) {
        for (IrBlock block : function.blocks()) verifyBlock(module, function, block, diagnostics);
    }

    private void verifyBlock(IrModule module, IrFunction function, IrBlock block, List<Diagnostic> diagnostics) {
        List<IrInstruction> instructions = block.instructions();
        if (instructions.isEmpty()) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Function " + function.name() + " has an empty IR block");
            return;
        }
        Map<Integer, Integer> depths = new HashMap<>();
        ArrayDeque<Integer> work = new ArrayDeque<>();
        depths.put(0, 0);
        work.add(0);
        int observedMax = 0;

        while (!work.isEmpty()) {
            int index = work.removeFirst();
            if (index < 0 || index >= instructions.size()) {
                error(diagnostics, DiagnosticCode.BYTECODE_BAD_JUMP, "Invalid control-flow target " + index + " in " + function.name());
                continue;
            }
            IrInstruction instruction = instructions.get(index);
            validateOperand(module, function, instruction, index, diagnostics);
            int before = depths.get(index);
            int required = requiredStack(instruction);
            if (before < required) {
                error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH,
                        "Stack underflow in function " + function.name() + " at " + index + ": needs " + required + ", has " + before);
                continue;
            }
            if (instruction instanceof IrInstruction.Return) {
                boolean unit = function.returnType() == null || function.returnType() == VeloraTypes.UNIT;
                if (unit && before != 0) error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH,
                        "Void return in " + function.name() + " has stack depth " + before);
                if (!unit && before != 1) error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH,
                        "Value return in " + function.name() + " requires one stack value, has " + before);
                observedMax = Math.max(observedMax, before);
                continue;
            }
            int after = before + stackDelta(instruction);
            if (after < 0) {
                error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH, "Negative stack depth in function " + function.name() + " at " + index);
                continue;
            }
            observedMax = Math.max(observedMax, Math.max(before, after));
            for (int successor : successors(instruction, index, instructions.size())) mergeDepth(function, successor, after, depths, work, diagnostics);
        }

        if (observedMax > function.maxStack()) error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH,
                "Function " + function.name() + " requires stack " + observedMax + " but maxStack is " + function.maxStack());
    }

    private void validateOperand(IrModule module, IrFunction function, IrInstruction instruction, int index, List<Diagnostic> diagnostics) {
        if (instruction instanceof IrInstruction.LoadLocal local) local(local.index(), function, index, diagnostics);
        else if (instruction instanceof IrInstruction.StoreLocal local) local(local.index(), function, index, diagnostics);
        else if (instruction instanceof IrInstruction.LoadSetting setting) range(setting.descriptorIndex(), module.settings().size(), "Setting", function, index, diagnostics);
        else if (instruction instanceof IrInstruction.Call call) functionCall(module, function, call.functionIndex(), call.argCount(), index, diagnostics);
        else if (instruction instanceof IrInstruction.Spawn spawn) functionCall(module, function, spawn.functionIndex(), spawn.argCount(), index, diagnostics);
        else if (instruction instanceof IrInstruction.CallApi call && (call.apiIndex() < 0 || call.argCount() < 0)) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Invalid API call in " + function.name() + " at " + index);
        else if (instruction instanceof IrInstruction.CallSuspend call && (call.apiIndex() < 0 || call.argCount() < 0)) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Invalid suspend API call in " + function.name() + " at " + index);
        else if (instruction instanceof IrInstruction.CallMember call && call.argCount() < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Invalid member call in " + function.name() + " at " + index);
        else if (instruction instanceof IrInstruction.CreateList list && list.elementCount() < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative list size in " + function.name() + " at " + index);
        else if (instruction instanceof IrInstruction.CreateSet set && set.elementCount() < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative set size in " + function.name() + " at " + index);
        else if (instruction instanceof IrInstruction.CreateMap map && map.entryCount() < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative map size in " + function.name() + " at " + index);
        else if (instruction instanceof IrInstruction.Jump jump) jump(jump.targetBlock(), function, index, diagnostics);
        else if (instruction instanceof IrInstruction.JumpIfFalse jump) jump(jump.targetBlock(), function, index, diagnostics);
        else if (instruction instanceof IrInstruction.JumpIfTrue jump) jump(jump.targetBlock(), function, index, diagnostics);
        else if (instruction instanceof IrInstruction.SetMember) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "SET_MEMBER is not valid for immutable script values in " + function.name());
    }

    private void local(int local, IrFunction function, int index, List<Diagnostic> diagnostics) {
        range(local, function.localCount(), "Local", function, index, diagnostics);
    }

    private void functionCall(IrModule module, IrFunction function, int target, int args, int index, List<Diagnostic> diagnostics) {
        range(target, module.functions().size(), "Function", function, index, diagnostics);
        if (args < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative argument count in " + function.name() + " at " + index);
        else if (target >= 0 && target < module.functions().size() && args != module.functions().get(target).parameters().size()) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Argument count mismatch for " + module.functions().get(target).name() + " in " + function.name() + " at " + index);
        }
    }

    private void jump(int target, IrFunction function, int index, List<Diagnostic> diagnostics) {
        if (target < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_JUMP, "Negative jump target in " + function.name() + " at " + index);
    }

    private void range(int value, int size, String label, IrFunction function, int index, List<Diagnostic> diagnostics) {
        if (value < 0 || value >= size) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND,
                label + " index " + value + " out of range in " + function.name() + " at " + index);
    }

    private int requiredStack(IrInstruction instruction) {
        return switch (instruction) {
            case IrInstruction.StoreLocal value -> 1;
            case IrInstruction.StoreField value -> 1;
            case IrInstruction.StoreStatic value -> 1;
            case IrInstruction.Pop value -> 1;
            case IrInstruction.Dup value -> 1;
            case IrInstruction.UnaryOp value -> 1;
            case IrInstruction.Not value -> 1;
            case IrInstruction.IsNull value -> 1;
            case IrInstruction.IsType value -> 1;
            case IrInstruction.JumpIfFalse value -> 1;
            case IrInstruction.JumpIfTrue value -> 1;
            case IrInstruction.GetMember value -> 1;
            case IrInstruction.Await value -> 1;
            case IrInstruction.Delay value -> 1;
            case IrInstruction.BinaryOp value -> 2;
            case IrInstruction.Compare value -> 2;
            case IrInstruction.GetIndex value -> 2;
            case IrInstruction.SetMember value -> 2;
            case IrInstruction.Call call -> call.argCount();
            case IrInstruction.CallApi call -> call.argCount();
            case IrInstruction.CallSuspend call -> call.argCount();
            case IrInstruction.CallMember call -> call.argCount() + 1;
            case IrInstruction.Spawn spawn -> spawn.argCount();
            case IrInstruction.CreateList list -> list.elementCount();
            case IrInstruction.CreateSet set -> set.elementCount();
            case IrInstruction.CreateMap map -> map.entryCount() * 2;
            default -> 0;
        };
    }

    private int stackDelta(IrInstruction instruction) {
        return switch (instruction) {
            case IrInstruction.Const value -> 1;
            case IrInstruction.LoadLocal value -> 1;
            case IrInstruction.LoadField value -> 1;
            case IrInstruction.LoadSetting value -> 1;
            case IrInstruction.LoadStatic value -> 1;
            case IrInstruction.LoadQualified value -> 1;
            case IrInstruction.Dup value -> 1;
            case IrInstruction.StoreLocal value -> -1;
            case IrInstruction.StoreField value -> -1;
            case IrInstruction.StoreStatic value -> -1;
            case IrInstruction.Pop value -> -1;
            case IrInstruction.Delay value -> -1;
            case IrInstruction.JumpIfFalse value -> -1;
            case IrInstruction.JumpIfTrue value -> -1;
            case IrInstruction.BinaryOp value -> -1;
            case IrInstruction.Compare value -> -1;
            case IrInstruction.GetIndex value -> -1;
            case IrInstruction.SetMember value -> -2;
            case IrInstruction.Call call -> 1 - call.argCount();
            case IrInstruction.CallApi call -> 1 - call.argCount();
            case IrInstruction.CallSuspend call -> 1 - call.argCount();
            case IrInstruction.CallMember call -> -call.argCount();
            case IrInstruction.Spawn spawn -> 1 - spawn.argCount();
            case IrInstruction.CreateList list -> 1 - list.elementCount();
            case IrInstruction.CreateSet set -> 1 - set.elementCount();
            case IrInstruction.CreateMap map -> 1 - map.entryCount() * 2;
            default -> 0;
        };
    }

    private List<Integer> successors(IrInstruction instruction, int index, int size) {
        int next = index + 1;
        if (instruction instanceof IrInstruction.Jump jump) return List.of(jump.targetBlock());
        if (instruction instanceof IrInstruction.JumpIfFalse jump) return next < size && jump.targetBlock() != next ? List.of(next, jump.targetBlock()) : List.of(jump.targetBlock());
        if (instruction instanceof IrInstruction.JumpIfTrue jump) return next < size && jump.targetBlock() != next ? List.of(next, jump.targetBlock()) : List.of(jump.targetBlock());
        if (next < size) return List.of(next);
        return List.of(size);
    }

    private void mergeDepth(IrFunction function, int index, int depth, Map<Integer, Integer> depths, ArrayDeque<Integer> work, List<Diagnostic> diagnostics) {
        if (index < 0) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_JUMP, "Negative control-flow target in " + function.name());
            return;
        }
        Integer previous = depths.putIfAbsent(index, depth);
        if (previous == null) work.add(index);
        else if (previous != depth) error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH,
                "Stack depth mismatch in " + function.name() + " at " + index + ": " + previous + " vs " + depth);
    }

    private void error(List<Diagnostic> diagnostics, DiagnosticCode code, String message) {
        diagnostics.add(Diagnostic.error(code, message, RANGE));
    }
}
