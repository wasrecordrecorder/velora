package io.velora.internal.bytecode;

import io.velora.internal.ir.*;
import io.velora.internal.vm.PrimitiveValue;
import io.velora.internal.vm.ScriptValue;
import io.velora.internal.vm.StringValue;

import java.util.*;

public final class BytecodeWriter {

    public CompiledModule write(IrModule module) {
        return write(module, "", "");
    }

    public CompiledModule write(IrModule module, String sourceHash) {
        return write(module, sourceHash, "");
    }

    public CompiledModule write(IrModule module, String sourceHash, String registryHash) {
        ConstantPool pool = new ConstantPool();
        List<CompiledFunction> functions = new ArrayList<>();
        List<String> lifecycleHooks = new ArrayList<>(module.lifecycleHooks());
        List<CompiledModule.EventHandlerInfo> eventHandlers = new ArrayList<>();
        for (var eh : module.eventHandlers()) {
            eventHandlers.add(new CompiledModule.EventHandlerInfo(
                    eh.eventReference(), eh.functionName(), eh.functionIndex(), eh.suspending()));
        }

        for (IrFunction fn : module.functions()) {
            functions.add(compileFunction(fn, pool));
        }

        List<CompiledModule.FieldInitializer> compiledInits = new ArrayList<>();
        for (var fi : module.fieldInitializers()) {
            compiledInits.add(new CompiledModule.FieldInitializer(
                    fi.fieldIndex(), fi.isStatic(), fi.initialValue()));
        }

        return new CompiledModule(
                module.scriptId(), module.scriptName(), module.version(), module.languageVersion(),
                sourceHash, registryHash, pool, functions, module.settings(),
                module.persistentFieldIds(), module.persistentFieldTypes(),
                module.persistentFieldIndices(), module.persistentFieldIsStatic(),
                lifecycleHooks, eventHandlers, compiledInits,
                module.author(), module.description()
        );
    }

    private ScriptValue irValueToScriptValue(IrValue value) {
        return switch (value) {
            case IrValue.IntVal v -> PrimitiveValue.of(v.value());
            case IrValue.LongVal v -> PrimitiveValue.of(v.value());
            case IrValue.FloatVal v -> PrimitiveValue.of(v.value());
            case IrValue.DoubleVal v -> PrimitiveValue.of(v.value());
            case IrValue.StringVal v -> new StringValue(v.value());
            case IrValue.BooleanVal v -> PrimitiveValue.of(v.value());
            case IrValue.DurationVal v -> PrimitiveValue.of(v.nanos());
            case IrValue.NullVal v -> PrimitiveValue.nullValue();
        };
    }

    private CompiledFunction compileFunction(IrFunction fn, ConstantPool pool) {
        List<Integer> code = new ArrayList<>();
        List<Integer> lineOffsets = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();

        Map<Integer, Integer> instrIndexToOffset = new HashMap<>();
        int instrIndex = 0;
        for (IrBlock block : fn.blocks()) {
            for (IrInstruction instr : block.instructions()) {
                int offset = code.size();
                instrIndexToOffset.put(instrIndex, offset);
                writeInstruction(instr, code, pool);
                if (instr instanceof IrInstruction.Line line) {
                    lineOffsets.add(offset);
                    lineNumbers.add(line.lineNumber());
                }
                instrIndex++;
            }
        }

        instrIndex = 0;
        for (IrBlock block : fn.blocks()) {
            for (IrInstruction instr : block.instructions()) {
                int instrOffset = instrIndexToOffset.get(instrIndex);
                if (instr instanceof IrInstruction.Jump j) {
                    code.set(instrOffset + 1, instrIndexToOffset.getOrDefault(j.targetBlock(), code.size()));
                } else if (instr instanceof IrInstruction.JumpIfFalse j) {
                    code.set(instrOffset + 1, instrIndexToOffset.getOrDefault(j.targetBlock(), code.size()));
                } else if (instr instanceof IrInstruction.JumpIfTrue j) {
                    code.set(instrOffset + 1, instrIndexToOffset.getOrDefault(j.targetBlock(), code.size()));
                }
                instrIndex++;
            }
        }

        int[] codeArray = code.stream().mapToInt(Integer::intValue).toArray();
        int[] lineOffsetsArray = lineOffsets.stream().mapToInt(Integer::intValue).toArray();
        int[] lineNumbersArray = lineNumbers.stream().mapToInt(Integer::intValue).toArray();

        return new CompiledFunction(fn.name(), fn.index(), fn.parameters().size(),
                fn.localCount(), fn.maxStack(), fn.suspending(), fn.isLifecycle(),
                codeArray, lineNumbersArray);
    }

    private void writeInstruction(IrInstruction instr, List<Integer> code, ConstantPool pool) {
        switch (instr) {
            case IrInstruction.Const c -> {
                code.add(Opcode.CONST.ordinal());
                code.add(constIndex(c.value(), pool));
            }
            case IrInstruction.LoadLocal l -> { code.add(Opcode.LOAD_LOCAL.ordinal()); code.add(l.index()); }
            case IrInstruction.StoreLocal s -> { code.add(Opcode.STORE_LOCAL.ordinal()); code.add(s.index()); }
            case IrInstruction.LoadField l -> { code.add(Opcode.LOAD_FIELD.ordinal()); code.add(l.index()); }
            case IrInstruction.StoreField s -> { code.add(Opcode.STORE_FIELD.ordinal()); code.add(s.index()); }
            case IrInstruction.LoadSetting l -> { code.add(Opcode.LOAD_SETTING.ordinal()); code.add(l.descriptorIndex()); }
            case IrInstruction.LoadStatic l -> { code.add(Opcode.LOAD_STATIC.ordinal()); code.add(l.index()); }
            case IrInstruction.StoreStatic s -> { code.add(Opcode.STORE_STATIC.ordinal()); code.add(s.index()); }
            case IrInstruction.Pop p -> code.add(Opcode.POP.ordinal());
            case IrInstruction.Dup d -> code.add(Opcode.DUP.ordinal());
            case IrInstruction.BinaryOp b -> code.add(binaryOpcode(b.operator()));
            case IrInstruction.UnaryOp u -> {
                if (u.operator().equals("!")) code.add(Opcode.NOT.ordinal());
                else code.add(Opcode.NEGATE.ordinal());
            }
            case IrInstruction.Compare c -> code.add(compareOpcode(c.operator()));
            case IrInstruction.Not n -> code.add(Opcode.NOT.ordinal());
            case IrInstruction.IsNull i -> code.add(Opcode.IS_NULL.ordinal());
            case IrInstruction.IsType i -> { code.add(Opcode.IS_TYPE.ordinal()); code.add(pool.addString(i.typeName())); }
            case IrInstruction.LoadQualified q -> { code.add(Opcode.LOAD_QUALIFIED.ordinal()); code.add(pool.addString(q.namespace())); code.add(pool.addString(q.member())); }
            case IrInstruction.Jump j -> { code.add(Opcode.JUMP.ordinal()); code.add(j.targetBlock()); }
            case IrInstruction.JumpIfFalse j -> { code.add(Opcode.JUMP_IF_FALSE.ordinal()); code.add(j.targetBlock()); }
            case IrInstruction.JumpIfTrue j -> { code.add(Opcode.JUMP_IF_TRUE.ordinal()); code.add(j.targetBlock()); }
            case IrInstruction.Return r -> code.add(Opcode.RETURN.ordinal());
            case IrInstruction.Call c -> { code.add(Opcode.CALL.ordinal()); code.add(c.functionIndex()); code.add(c.argCount()); }
            case IrInstruction.CallApi c -> { code.add(Opcode.CALL_API.ordinal()); code.add(c.apiIndex()); code.add(c.argCount()); }
            case IrInstruction.CallSuspend c -> { code.add(Opcode.CALL_SUSPEND.ordinal()); code.add(c.apiIndex()); code.add(c.argCount()); }
            case IrInstruction.CallMember c -> { code.add(Opcode.CALL_MEMBER.ordinal()); code.add(pool.addString(c.memberName())); code.add(c.argCount()); }
            case IrInstruction.GetMember g -> { code.add(Opcode.GET_MEMBER.ordinal()); code.add(pool.addString(g.memberName())); }
            case IrInstruction.SetMember s -> { code.add(Opcode.SET_MEMBER.ordinal()); code.add(pool.addString(s.memberName())); }
            case IrInstruction.CreateList c -> { code.add(Opcode.CREATE_LIST.ordinal()); code.add(c.elementCount()); }
            case IrInstruction.CreateSet c -> { code.add(Opcode.CREATE_SET.ordinal()); code.add(c.elementCount()); }
            case IrInstruction.CreateMap c -> { code.add(Opcode.CREATE_MAP.ordinal()); code.add(c.entryCount()); }
            case IrInstruction.GetIndex g -> code.add(Opcode.GET_INDEX.ordinal());
            case IrInstruction.Spawn s -> { code.add(Opcode.SPAWN.ordinal()); code.add(s.functionIndex()); code.add(s.argCount()); }
            case IrInstruction.Await a -> code.add(Opcode.AWAIT.ordinal());
            case IrInstruction.Delay d -> code.add(Opcode.DELAY.ordinal());
            case IrInstruction.Yield y -> code.add(Opcode.YIELD.ordinal());
            case IrInstruction.CheckCancelled c -> code.add(Opcode.CHECK_CANCELLED.ordinal());
            case IrInstruction.Line l -> { code.add(Opcode.LINE.ordinal()); code.add(l.lineNumber()); }
            case IrInstruction.Breakpoint b -> code.add(Opcode.BREAKPOINT.ordinal());
        }
    }

    private int constIndex(IrValue value, ConstantPool pool) {
        return switch (value) {
            case IrValue.IntVal v -> pool.addInt(v.value());
            case IrValue.LongVal v -> pool.addLong(v.value());
            case IrValue.FloatVal v -> pool.addFloat(v.value());
            case IrValue.DoubleVal v -> pool.addDouble(v.value());
            case IrValue.StringVal v -> pool.addString(v.value());
            case IrValue.BooleanVal v -> pool.addBoolean(v.value());
            case IrValue.DurationVal v -> pool.addDuration(v.nanos());
            case IrValue.NullVal v -> pool.addNull();
        };
    }

    private int binaryOpcode(String op) {
        return switch (op) {
            case "+" -> Opcode.ADD.ordinal();
            case "-" -> Opcode.SUB.ordinal();
            case "*" -> Opcode.MUL.ordinal();
            case "/" -> Opcode.DIV.ordinal();
            case "%" -> Opcode.MOD.ordinal();
            default -> throw new IllegalArgumentException("Unknown binary operator: " + op);
        };
    }

    private int compareOpcode(String op) {
        return switch (op) {
            case "==" -> Opcode.EQUAL.ordinal();
            case "!=" -> Opcode.NOT_EQUAL.ordinal();
            case "<" -> Opcode.LESS.ordinal();
            case "<=" -> Opcode.LESS_EQUAL.ordinal();
            case ">" -> Opcode.GREATER.ordinal();
            case ">=" -> Opcode.GREATER_EQUAL.ordinal();
            case "is" -> Opcode.EQUAL.ordinal();
            default -> throw new IllegalArgumentException("Unknown comparison operator: " + op);
        };
    }
}
