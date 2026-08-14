package io.velora.internal.ir;

import io.velora.api.type.VeloraType;

public sealed interface IrInstruction {

    record Const(IrValue value) implements IrInstruction {}
    record LoadLocal(int index, VeloraType type) implements IrInstruction {}
    record StoreLocal(int index, VeloraType type) implements IrInstruction {}
    record LoadField(int index, VeloraType type) implements IrInstruction {}
    record StoreField(int index, VeloraType type) implements IrInstruction {}
    record LoadStatic(int index, VeloraType type) implements IrInstruction {}
    record StoreStatic(int index, VeloraType type) implements IrInstruction {}
    record LoadSetting(int descriptorIndex, VeloraType type) implements IrInstruction {}
    record Pop() implements IrInstruction {}
    record Dup() implements IrInstruction {}
    record BinaryOp(String operator, VeloraType resultType) implements IrInstruction {}
    record UnaryOp(String operator, VeloraType resultType) implements IrInstruction {}
    record Compare(String operator) implements IrInstruction {}
    record Not() implements IrInstruction {}
    record IsNull() implements IrInstruction {}
    record IsType(String typeName) implements IrInstruction {}
    record LoadQualified(String namespace, String member) implements IrInstruction {}
    record Jump(int targetBlock) implements IrInstruction {}
    record JumpIfFalse(int targetBlock) implements IrInstruction {}
    record JumpIfTrue(int targetBlock) implements IrInstruction {}
    record Return() implements IrInstruction {}
    record Call(int functionIndex, int argCount, VeloraType returnType) implements IrInstruction {}
    record CallApi(int apiIndex, int argCount, VeloraType returnType) implements IrInstruction {}
    record CallSuspend(int apiIndex, int argCount, VeloraType returnType) implements IrInstruction {}
    record GetMember(String memberName, VeloraType resultType) implements IrInstruction {}
    record SetMember(String memberName) implements IrInstruction {}
    record CreateList(int elementCount, VeloraType elementType) implements IrInstruction {}
    record CreateMap(int entryCount, VeloraType keyType, VeloraType valueType) implements IrInstruction {}
    record GetIndex(VeloraType resultType) implements IrInstruction {}
    record Spawn(int functionIndex, int argCount) implements IrInstruction {}
    record Await() implements IrInstruction {}
    record Delay() implements IrInstruction {}
    record Yield() implements IrInstruction {}
    record CheckCancelled() implements IrInstruction {}
    record Line(int lineNumber) implements IrInstruction {}
    record Breakpoint() implements IrInstruction {}
}
