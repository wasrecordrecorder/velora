package io.velora.internal.bytecode;

import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.compiler.SourceRange;
import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionDescriptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BytecodeVerifier {

    private static final Opcode[] OPCODES = Opcode.values();
    private final ApiRegistry apiRegistry;
    private static final SourceRange RANGE = SourceRange.of("bc", 0, 0);

    public BytecodeVerifier() {
        this(null);
    }

    public BytecodeVerifier(ApiRegistry apiRegistry) {
        this.apiRegistry = apiRegistry;
    }

    public List<Diagnostic> verify(CompiledModule module) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < module.functions().size(); i++) {
            CompiledFunction function = module.functions().get(i);
            if (function.index() != i) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Function index mismatch for " + function.name() + ": " + function.index() + " != " + i);
            if (function.name() == null || function.name().isBlank()) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Function name cannot be empty at index " + i);
            else if (!names.add(function.name())) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Duplicate function name: " + function.name());
            verifyFunction(function, module, diagnostics);
        }
        verifyModuleMetadata(module, diagnostics);
        return diagnostics;
    }

    private void verifyModuleMetadata(CompiledModule module, List<Diagnostic> diagnostics) {
        int persistent = module.persistentFieldIds().size();
        if (module.persistentFieldTypes().size() != persistent || module.persistentFieldIndices().size() != persistent || module.persistentFieldIsStatic().size() != persistent) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Persistent field metadata sizes do not match");
        }
        Set<String> persistentIds = new HashSet<>();
        for (int i = 0; i < persistent; i++) {
            String id = module.persistentFieldIds().get(i);
            if (id == null || id.isBlank()) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Persistent field id cannot be empty at index " + i);
            else if (!persistentIds.add(id)) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Duplicate persistent field id: " + id);
            if (i < module.persistentFieldTypes().size() && (module.persistentFieldTypes().get(i) == null || module.persistentFieldTypes().get(i).isBlank())) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Persistent field type cannot be empty at index " + i);
            if (i < module.persistentFieldIndices().size() && module.persistentFieldIndices().get(i) < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative persistent field index at " + i);
        }

        Set<String> hooks = new HashSet<>();
        Set<String> validHooks = Set.of("ON_LOAD", "ON_ENABLE", "ON_RUN", "ON_DISABLE", "ON_UNLOAD");
        for (String hook : module.lifecycleHooks()) {
            if (hook == null || !validHooks.contains(hook)) {
                error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Invalid lifecycle hook: " + hook);
                continue;
            }
            if (!hooks.add(hook)) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Duplicate lifecycle hook: " + hook);
            CompiledFunction function = module.functionByName(hook);
            if (function == null || !function.isLifecycle() || function.parameterCount() != 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Lifecycle hook " + hook + " does not reference a valid lifecycle function");
        }

        for (CompiledModule.EventHandlerInfo handler : module.eventHandlers()) {
            if (handler.eventReference() == null || handler.eventReference().isBlank()) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Event handler reference cannot be empty");
            CompiledFunction function = module.function(handler.functionIndex());
            if (function == null) {
                error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Event handler function index out of range: " + handler.functionIndex());
                continue;
            }
            if (!function.name().equals(handler.functionName())) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Event handler function name mismatch at index " + handler.functionIndex());
            if (module.lifecycleHooks().contains(function.name())) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Event handler cannot reference a lifecycle hook: " + function.name());
            if (function.suspending() != handler.suspending()) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Event handler suspend metadata mismatch for " + function.name());
        }

        Set<String> initializers = new HashSet<>();
        for (CompiledModule.FieldInitializer initializer : module.fieldInitializers()) {
            if (initializer.fieldIndex() < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative field initializer index: " + initializer.fieldIndex());
            String key = (initializer.isStatic() ? "S" : "I") + initializer.fieldIndex();
            if (!initializers.add(key)) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Duplicate field initializer: " + key);
        }
    }

    private void verifyFunction(CompiledFunction function, CompiledModule module, List<Diagnostic> diagnostics) {
        int[] code = function.code();
        int[] lines = function.lineNumbers();
        if (function.localCount() < function.parameterCount() || function.maxStack() < 0 || lines == null || lines.length != 0 && lines.length != code.length) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Invalid function metadata in " + function.name());
            return;
        }
        for (int line : lines) {
            if (line < 0) {
                error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative source line in " + function.name());
                return;
            }
        }

        int errorStart = diagnostics.size();
        if (code.length == 0) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Function " + function.name() + " has no bytecode");
            return;
        }
        Map<Integer, Instruction> instructions = decode(function, module, diagnostics);
        if (instructions == null) return;

        Set<Integer> boundaries = new HashSet<>(instructions.keySet());
        boundaries.add(code.length);
        for (Instruction instruction : instructions.values()) {
            if (instruction.isJump()) {
                int target = instruction.operands()[0];
                if (!boundaries.contains(target)) error(diagnostics, DiagnosticCode.BYTECODE_BAD_JUMP, "Jump target " + target + " is not an instruction boundary in " + function.name());
            }
        }
        if (diagnostics.size() != errorStart) return;

        Map<Integer, Integer> depths = new HashMap<>();
        ArrayDeque<Integer> work = new ArrayDeque<>();
        depths.put(0, 0);
        work.add(0);
        int observedMax = 0;

        while (!work.isEmpty()) {
            int offset = work.removeFirst();
            if (offset == code.length) {
                error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Function " + function.name() + " can fall through without RETURN");
                continue;
            }
            Instruction instruction = instructions.get(offset);
            if (instruction == null) {
                error(diagnostics, DiagnosticCode.BYTECODE_BAD_JUMP, "Control flow enters an operand in " + function.name() + " at " + offset);
                continue;
            }
            int before = depths.get(offset);
            if (instruction.opcode() == Opcode.RETURN && before > 1) {
                error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH, "RETURN in " + function.name() + " leaves " + before + " values on the stack");
                continue;
            }
            int required = requiredStack(instruction);
            if (before < required) {
                error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH, "Stack underflow in " + function.name() + " at " + offset + ": needs " + required + ", has " + before);
                continue;
            }
            int after = before + stackDelta(instruction);
            observedMax = Math.max(observedMax, Math.max(before, after));
            if (after < 0) {
                error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH, "Negative stack depth in " + function.name() + " at " + offset);
                continue;
            }
            for (int successor : successors(instruction, code.length)) mergeDepth(function, successor, after, depths, work, diagnostics);
        }

        if (observedMax > function.maxStack()) error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH, "Function " + function.name() + " requires stack " + observedMax + " but maxStack is " + function.maxStack());
    }

    private Map<Integer, Instruction> decode(CompiledFunction function, CompiledModule module, List<Diagnostic> diagnostics) {
        int[] code = function.code();
        Map<Integer, Instruction> instructions = new HashMap<>();
        for (int ip = 0; ip < code.length;) {
            int ordinal = code[ip];
            if (ordinal < 0 || ordinal >= OPCODES.length) {
                error(diagnostics, DiagnosticCode.BYTECODE_INVALID_OPCODE, "Invalid opcode " + ordinal + " in " + function.name() + " at " + ip);
                return null;
            }
            Opcode opcode = OPCODES[ordinal];
            if (ip + opcode.operandWords() >= code.length) {
                error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Truncated operand for " + opcode + " in " + function.name() + " at " + ip);
                return null;
            }
            int[] operands = Arrays.copyOfRange(code, ip + 1, ip + 1 + opcode.operandWords());
            Instruction instruction = new Instruction(ip, opcode, operands);
            instructions.put(ip, instruction);
            validateOperands(function, module, instruction, diagnostics);
            ip += opcode.instructionWords();
        }
        return instructions;
    }

    private void validateOperands(CompiledFunction function, CompiledModule module, Instruction instruction, List<Diagnostic> diagnostics) {
        Opcode opcode = instruction.opcode();
        int[] operands = instruction.operands();
        switch (opcode) {
            case CONST -> range(operands[0], module.constantPool().size(), "Constant", function, instruction, diagnostics);
            case LOAD_LOCAL, STORE_LOCAL -> range(operands[0], function.localCount(), "Local", function, instruction, diagnostics);
            case LOAD_FIELD, STORE_FIELD, LOAD_STATIC, STORE_STATIC -> {
                if (operands[0] < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative field index in " + function.name() + " at " + instruction.offset());
            }
            case LOAD_SETTING -> range(operands[0], module.settings().size(), "Setting", function, instruction, diagnostics);
            case CALL, SPAWN -> {
                range(operands[0], module.functions().size(), "Function", function, instruction, diagnostics);
                if (operands[1] < 0) {
                    error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative argument count in " + function.name() + " at " + instruction.offset());
                } else if (operands[0] >= 0 && operands[0] < module.functions().size() && operands[1] != module.function(operands[0]).parameterCount()) {
                    error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Argument count mismatch for " + module.function(operands[0]).name() + " in " + function.name() + " at " + instruction.offset());
                }
            }
            case CALL_API, CALL_SUSPEND -> validateApiCall(function, instruction, diagnostics);
            case CALL_MEMBER -> {
                validateStringOperand(operands[0], module, function, instruction, diagnostics);
                if (operands[1] < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative member argument count in " + function.name() + " at " + instruction.offset());
            }
            case CREATE_LIST, CREATE_SET, CREATE_MAP -> {
                if (operands[0] < 0 || operands[0] > 1_000_000) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Invalid collection size in " + function.name() + " at " + instruction.offset());
            }
            case GET_MEMBER, SET_MEMBER, IS_TYPE -> validateStringOperand(operands[0], module, function, instruction, diagnostics);
            case LOAD_QUALIFIED -> {
                validateStringOperand(operands[0], module, function, instruction, diagnostics);
                validateStringOperand(operands[1], module, function, instruction, diagnostics);
            }
            case LINE -> {
                if (operands[0] < 0) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Negative line number in " + function.name() + " at " + instruction.offset());
            }
            default -> {}
        }
        if (opcode == Opcode.SET_MEMBER) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "SET_MEMBER is not valid for immutable script values in " + function.name());
    }

    private void validateApiCall(CompiledFunction function, Instruction instruction, List<Diagnostic> diagnostics) {
        int index = instruction.operands()[0];
        int argumentCount = instruction.operands()[1];
        if (index < 0 || argumentCount < 0) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Invalid API call operand in " + function.name() + " at " + instruction.offset());
            return;
        }
        if (apiRegistry == null) return;
        FunctionDescriptor descriptor = apiRegistry.findByIndex(index);
        if (descriptor == null) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "API index " + index + " out of range in " + function.name() + " at " + instruction.offset());
            return;
        }
        int required = (int) descriptor.parameters().stream().filter(parameter -> parameter.required() && !parameter.hasDefault()).count();
        if (argumentCount < required || argumentCount > descriptor.parameters().size()) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "Argument count mismatch for " + descriptor.qualifiedName() + " in " + function.name() + " at " + instruction.offset());
        }
        boolean suspendOpcode = instruction.opcode() == Opcode.CALL_SUSPEND;
        if (descriptor.suspending() != suspendOpcode) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "API call mode mismatch for " + descriptor.qualifiedName() + " in " + function.name() + " at " + instruction.offset());
        }
    }

    private void validateStringOperand(int index, CompiledModule module, CompiledFunction function, Instruction instruction, List<Diagnostic> diagnostics) {
        range(index, module.constantPool().size(), "String constant", function, instruction, diagnostics);
        if (index >= 0 && index < module.constantPool().size() && module.constantPool().tag(index) != ConstantPool.Tag.STRING) {
            error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, "String operand must be a string constant in " + function.name() + " at " + instruction.offset());
        }
    }

    private void range(int index, int size, String label, CompiledFunction function, Instruction instruction, List<Diagnostic> diagnostics) {
        if (index < 0 || index >= size) error(diagnostics, DiagnosticCode.BYTECODE_BAD_OPERAND, label + " index " + index + " out of range in " + function.name() + " at " + instruction.offset());
    }

    private int requiredStack(Instruction instruction) {
        Opcode opcode = instruction.opcode();
        return switch (opcode) {
            case STORE_LOCAL, STORE_FIELD, STORE_STATIC, POP, DUP, NEGATE, NOT, IS_NULL, IS_TYPE, JUMP_IF_FALSE, JUMP_IF_TRUE, GET_MEMBER, AWAIT, DELAY -> 1;
            case ADD, SUB, MUL, DIV, MOD, EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, GET_INDEX, SET_MEMBER -> 2;
            case CALL, CALL_API, CALL_SUSPEND, SPAWN -> instruction.operands()[1];
            case CALL_MEMBER -> instruction.operands()[1] + 1;
            case CREATE_LIST, CREATE_SET -> instruction.operands()[0];
            case CREATE_MAP -> instruction.operands()[0] * 2;
            default -> 0;
        };
    }

    private int stackDelta(Instruction instruction) {
        Opcode opcode = instruction.opcode();
        return switch (opcode) {
            case CONST, NULL, TRUE, FALSE, LOAD_LOCAL, LOAD_FIELD, LOAD_SETTING, LOAD_STATIC, LOAD_QUALIFIED, DUP -> 1;
            case STORE_LOCAL, STORE_FIELD, STORE_STATIC, POP, DELAY, JUMP_IF_FALSE, JUMP_IF_TRUE -> -1;
            case ADD, SUB, MUL, DIV, MOD, EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, GET_INDEX -> -1;
            case SET_MEMBER -> -2;
            case CALL, CALL_API, CALL_SUSPEND, SPAWN -> 1 - instruction.operands()[1];
            case CALL_MEMBER -> -instruction.operands()[1];
            case CREATE_LIST, CREATE_SET -> 1 - instruction.operands()[0];
            case CREATE_MAP -> 1 - instruction.operands()[0] * 2;
            default -> 0;
        };
    }

    private List<Integer> successors(Instruction instruction, int codeLength) {
        int next = instruction.offset() + instruction.opcode().instructionWords();
        return switch (instruction.opcode()) {
            case RETURN -> List.of();
            case JUMP, LOOP -> List.of(instruction.operands()[0]);
            case JUMP_IF_FALSE, JUMP_IF_TRUE -> instruction.operands()[0] == next ? List.of(next) : List.of(next, instruction.operands()[0]);
            default -> next <= codeLength ? List.of(next) : List.of();
        };
    }

    private void mergeDepth(CompiledFunction function, int offset, int depth, Map<Integer, Integer> depths, ArrayDeque<Integer> work, List<Diagnostic> diagnostics) {
        Integer previous = depths.putIfAbsent(offset, depth);
        if (previous == null) {
            work.add(offset);
        } else if (previous != depth) {
            error(diagnostics, DiagnosticCode.BYTECODE_STACK_MISMATCH, "Stack depth mismatch in " + function.name() + " at " + offset + ": " + previous + " vs " + depth);
        }
    }

    private void error(List<Diagnostic> diagnostics, DiagnosticCode code, String message) {
        diagnostics.add(Diagnostic.error(code, message, RANGE));
    }

    private record Instruction(int offset, Opcode opcode, int[] operands) {
        private boolean isJump() {
            return opcode == Opcode.JUMP || opcode == Opcode.JUMP_IF_FALSE || opcode == Opcode.JUMP_IF_TRUE || opcode == Opcode.LOOP;
        }
    }
}
