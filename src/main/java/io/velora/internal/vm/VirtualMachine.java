package io.velora.internal.vm;

import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionContext;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.function.FunctionInvoker;
import io.velora.api.function.ScriptThread;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.task.TaskState;
import io.velora.api.task.VeloraTask;
import io.velora.internal.bytecode.*;
import io.velora.internal.security.ResourceLimitViolation;

import java.util.*;

public final class VirtualMachine {

    private static final Opcode[] OPCODES = Opcode.values();
    private final ApiRegistry apiRegistry;
    private final List<SettingDescriptor> settings;
    private final io.velora.internal.setting.SettingStore settingStore;
    private final int instructionLimit;
    private final int maxCallDepth;
    private final int maxStringLength;
    private final int maxCollectionElements;
    private final int maxCollectionDepth;
    private final Map<Integer, ScriptValue> staticFields = new HashMap<>();
    private final Map<Integer, ScriptValue> instanceFields = new HashMap<>();
    private CompiledModule currentModule;

    public VirtualMachine(ApiRegistry apiRegistry, List<SettingDescriptor> settings, int instructionLimit) {
        this(apiRegistry, settings, null, instructionLimit);
    }

    public VirtualMachine(ApiRegistry apiRegistry, List<SettingDescriptor> settings,
                          io.velora.internal.setting.SettingStore settingStore, int instructionLimit) {
        this(apiRegistry, settings, settingStore, instructionLimit, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public VirtualMachine(ApiRegistry apiRegistry, List<SettingDescriptor> settings,
                          io.velora.internal.setting.SettingStore settingStore, int instructionLimit,
                          int maxCallDepth, int maxStringLength) {
        this(apiRegistry, settings, settingStore, instructionLimit, maxCallDepth, maxStringLength, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public VirtualMachine(ApiRegistry apiRegistry, List<SettingDescriptor> settings,
                          io.velora.internal.setting.SettingStore settingStore, int instructionLimit,
                          int maxCallDepth, int maxStringLength, int maxCollectionElements, int maxCollectionDepth) {
        this.apiRegistry = apiRegistry;
        this.settings = settings;
        this.settingStore = settingStore;
        this.instructionLimit = instructionLimit;
        this.maxCallDepth = maxCallDepth;
        this.maxStringLength = maxStringLength;
        this.maxCollectionElements = maxCollectionElements;
        this.maxCollectionDepth = maxCollectionDepth;
    }

    private void initializeFields(CompiledModule module) {
        if (module == currentModule) return;
        currentModule = module;
        staticFields.clear();
        instanceFields.clear();
        for (CompiledModule.FieldInitializer fi : module.fieldInitializers()) {
            if (fi.isStatic()) {
                staticFields.put(fi.fieldIndex(), copyValue(fi.initialValue()));
            } else {
                instanceFields.put(fi.fieldIndex(), copyValue(fi.initialValue()));
            }
        }
    }

    public VmExecutionResult execute(CompiledModule module, int functionIndex, ScriptValue[] args) {
        initializeFields(module);
        CompiledFunction fn = module.function(functionIndex);
        if (fn == null) {
            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                    "Function not found: " + functionIndex, 0, 0), 0, 0);
        }
        ValueStack stack = new ValueStack(Math.max(fn.maxStack(), 16));
        Deque<CallFrame> callStack = new ArrayDeque<>();
        CallFrame rootFrame = new CallFrame(fn, 0, 0);
        for (int i = 0; i < Math.min(args.length, fn.parameterCount()); i++) {
            rootFrame.locals[i] = args[i];
        }
        callStack.push(rootFrame);
        return executeInternal(module, 0, null, stack, callStack, 0);
    }

    public VmExecutionResult execute(CompiledModule module, long fiberId, VmHost host,
                                      ValueStack stack, Deque<CallFrame> callStack, int startInstructions) {
        initializeFields(module);
        return executeInternal(module, fiberId, host, stack, callStack, startInstructions);
    }

    private VmExecutionResult executeInternal(CompiledModule module, long fiberId, VmHost host,
                                              ValueStack stack, Deque<CallFrame> callStack, int startInstructions) {
        int instructions = startInstructions;
        long startTime = System.nanoTime();

        try {
            while (!callStack.isEmpty()) {
                CallFrame frame = callStack.peek();
                int[] code = frame.function.code();

                if (frame.ip >= code.length) {
                    callStack.pop();
                    continue;
                }

                if (instructions >= instructionLimit) {
                    if (host == null) {
                        return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_RESOURCE_LIMIT,
                                "Instruction limit exceeded", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                    }
                    return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.INSTRUCTION_LIMIT,
                            0, instructions, System.nanoTime() - startTime);
                }

                int[] lineNumbers = frame.function.lineNumbers();
                if (frame.ip < lineNumbers.length && lineNumbers[frame.ip] > 0) frame.line(lineNumbers[frame.ip]);
                int ordinal = code[frame.ip];
                if (ordinal < 0 || ordinal >= OPCODES.length) {
                    return VmExecutionResult.failure(VmError.of(DiagnosticCode.BYTECODE_INVALID_OPCODE,
                            "Invalid opcode: " + ordinal, currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                }
                Opcode op = OPCODES[ordinal];
                if (frame.ip + op.operandWords() >= code.length) {
                    return VmExecutionResult.failure(VmError.of(DiagnosticCode.BYTECODE_BAD_OPERAND,
                            "Truncated operand for " + op, currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                }
                int operand0 = op.operandWords() > 0 ? code[frame.ip + 1] : 0;
                int operand1 = op.operandWords() > 1 ? code[frame.ip + 2] : 0;
                frame.ip += op.instructionWords();
                instructions++;

                switch (op) {
                    case CONST -> {
                        ConstantPool.Tag tag = module.constantPool().tag(operand0);
                        ScriptValue cv = constToValue(module.constantPool(), operand0, tag);
                        enforceValueLimits(cv);
                        stack.push(cv);
                    }
                    case NULL -> stack.push(PrimitiveValue.nullValue());
                    case TRUE -> stack.push(PrimitiveValue.of(true));
                    case FALSE -> stack.push(PrimitiveValue.of(false));
                    case LOAD_LOCAL -> stack.push(frame.locals[operand0]);
                    case STORE_LOCAL -> frame.locals[operand0] = stack.pop();
                    case LOAD_FIELD -> {
                        if (host != null) {
                            stack.push(host.loadField(operand0));
                        } else {
                            stack.push(instanceFields.getOrDefault(operand0, PrimitiveValue.nullValue()));
                        }
                    }
                    case STORE_FIELD -> {
                        ScriptValue val = stack.pop();
                        if (host != null) host.storeField(operand0, val);
                        else instanceFields.put(operand0, val);
                    }
                    case LOAD_SETTING -> {
                        ScriptValue value;
                        if (settingStore != null) {
                            var sv = settingStore.getByIndex(operand0);
                            value = sv != null ? javaToValue(sv.type(), sv.value()) : PrimitiveValue.nullValue();
                        } else if (operand0 < settings.size()) {
                            SettingDescriptor descriptor = settings.get(operand0);
                            value = javaToValue(descriptor.type(), descriptor.defaultValue());
                        } else {
                            value = PrimitiveValue.nullValue();
                        }
                        enforceValueLimits(value);
                        stack.push(value);
                    }
                    case LOAD_STATIC -> {
                        if (host != null) {
                            stack.push(host.loadStatic(operand0));
                        } else {
                            stack.push(staticFields.getOrDefault(operand0, PrimitiveValue.nullValue()));
                        }
                    }
                    case STORE_STATIC -> {
                        ScriptValue sval = stack.pop();
                        if (host != null) host.storeStatic(operand0, sval);
                        else staticFields.put(operand0, sval);
                    }
                    case POP -> stack.pop();
                    case DUP -> stack.dup();
                    case ADD -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(add(a, b)); }
                    case SUB -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(sub(a, b)); }
                    case MUL -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(mul(a, b)); }
                    case DIV -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(div(a, b)); }
                    case MOD -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(mod(a, b)); }
                    case NEGATE -> { ScriptValue a = stack.pop(); stack.push(negate(a)); }
                    case EQUAL -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(PrimitiveValue.of(equal(a, b))); }
                    case NOT_EQUAL -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(PrimitiveValue.of(!equal(a, b))); }
                    case LESS -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(PrimitiveValue.of(compare(a, b) < 0)); }
                    case LESS_EQUAL -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(PrimitiveValue.of(compare(a, b) <= 0)); }
                    case GREATER -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(PrimitiveValue.of(compare(a, b) > 0)); }
                    case GREATER_EQUAL -> { ScriptValue b = stack.pop(); ScriptValue a = stack.pop(); stack.push(PrimitiveValue.of(compare(a, b) >= 0)); }
                    case NOT -> { ScriptValue a = stack.pop(); stack.push(PrimitiveValue.of(!toBoolean(a))); }
                    case IS_NULL -> { ScriptValue a = stack.pop(); stack.push(PrimitiveValue.of(a.isNull())); }
                    case IS_TYPE -> { ScriptValue a = stack.pop(); stack.push(PrimitiveValue.of(matchesType(a, module.constantPool().stringValue(operand0)))); }
                    case LOAD_QUALIFIED -> {
                        if (host == null) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR, "Qualified host value requires a scheduler", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        ScriptValue value = host.loadQualified(module.constantPool().stringValue(operand0), module.constantPool().stringValue(operand1));
                        if (value == null) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR, "Qualified value is not registered", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        enforceValueLimits(value);
                        stack.push(value);
                    }
                    case JUMP -> frame.ip = operand0;
                    case JUMP_IF_FALSE -> { if (!toBoolean(stack.pop())) frame.ip = operand0; }
                    case JUMP_IF_TRUE -> { if (toBoolean(stack.pop())) frame.ip = operand0; }
                    case LOOP -> frame.ip = operand0;
                    case RETURN -> {
                        ScriptValue retVal = stack.isEmpty() ? PrimitiveValue.nullValue() : stack.pop();
                        callStack.pop();
                        if (callStack.isEmpty()) {
                            return VmExecutionResult.success(retVal, instructions, System.nanoTime() - startTime);
                        }
                        stack.push(retVal);
                    }
                    case CALL -> {
                        int funcIdx = operand0;
                        int argCount = operand1;
                        if (callStack.size() >= maxCallDepth) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_RESOURCE_LIMIT,
                                    "Call depth limit exceeded", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        CompiledFunction target = module.function(funcIdx);
                        if (target == null) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "Function not found: " + funcIdx, currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        ScriptValue[] callArgs = new ScriptValue[argCount];
                        for (int i = argCount - 1; i >= 0; i--) {
                            callArgs[i] = stack.pop();
                        }
                        CallFrame newFrame = new CallFrame(target, stack.size(), 0);
                        for (int i = 0; i < Math.min(argCount, target.parameterCount()); i++) {
                            newFrame.locals[i] = callArgs[i];
                        }
                        callStack.push(newFrame);
                    }
                    case CALL_API -> {
                        int apiIdx = operand0;
                        int argCount = operand1;
                        FunctionDescriptor fd = apiRegistry.findByIndex(apiIdx);
                        if (fd == null || fd.invoker() == null) {
                            for (int i = 0; i < argCount; i++) stack.pop();
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "API function not found: " + apiIdx, currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        if (host != null && !host.consumeApiCost(fiberId, fd.cost())) {
                            for (int i = 0; i < argCount; i++) stack.pop();
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_RESOURCE_LIMIT,
                                    "API cost limit exceeded", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        if (fd.thread() == ScriptThread.MAIN && host != null && !host.isMainThread()) {
                            for (int i = 0; i < argCount; i++) stack.pop();
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "MAIN API function called outside the main thread: " + fd.qualifiedName(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        if (fd.thread() == ScriptThread.WORKER) {
                            for (int i = 0; i < argCount; i++) stack.pop();
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "WORKER API function must be suspending: " + fd.qualifiedName(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        Object[] apiArgs = new Object[argCount];
                        for (int i = argCount - 1; i >= 0; i--) apiArgs[i] = stack.pop().boxed();
                        try {
                            Object result = fd.invoker().invoke(new SimpleFunctionContext(fd, apiArgs, module.scriptId(), fiberId));
                            ScriptValue sv = apiResult(fd, result);
                            enforceValueLimits(sv);
                            stack.push(sv);
                        } catch (ResourceLimitException e) {
                            throw e;
                        } catch (Throwable t) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                    }
                    case CALL_SUSPEND -> {
                        int apiIdx = operand0;
                        int argCount = operand1;
                        FunctionDescriptor fd = apiRegistry.findByIndex(apiIdx);
                        if (fd == null || fd.invoker() == null) {
                            for (int i = 0; i < argCount; i++) stack.pop();
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "API function not found: " + apiIdx, currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        if (host != null && !host.consumeApiCost(fiberId, fd.cost())) {
                            for (int i = 0; i < argCount; i++) stack.pop();
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_RESOURCE_LIMIT,
                                    "API cost limit exceeded", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        Object[] apiArgs = new Object[argCount];
                        for (int i = argCount - 1; i >= 0; i--) apiArgs[i] = stack.pop().boxed();
                        FunctionContext context = new SimpleFunctionContext(fd, apiArgs, module.scriptId(), fiberId);
                        if (fd.thread() == ScriptThread.MAIN && host != null && !host.isMainThread()) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "MAIN API function called outside the main thread: " + fd.qualifiedName(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        if (fd.thread() == ScriptThread.WORKER) {
                            if (host == null) {
                                return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                        "WORKER API requires a scheduler", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                            }
                            long taskId = host.watchWorkerCall(fiberId, fd, context);
                            if (taskId == 0) {
                                return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_RESOURCE_LIMIT,
                                        "Worker task could not be scheduled", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                            }
                            return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.AWAIT,
                                    taskId, instructions, System.nanoTime() - startTime);
                        }
                        try {
                            Object result = fd.invoker().invoke(context);
                            if (result instanceof VeloraTask<?> task) {
                                if (task.state() == TaskState.PENDING) {
                                    if (host == null) {
                                        return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                                "Suspending API requires a scheduler", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                                    }
                                    long taskId = host.watchTask(fiberId, task, fd.returnType());
                                    if (taskId == 0) {
                                        return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_RESOURCE_LIMIT,
                                                "Task limit exceeded", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                                    }
                                    return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.AWAIT,
                                            taskId, instructions, System.nanoTime() - startTime);
                                }
                                if (task.state() == TaskState.SUCCEEDED) {
                                    ScriptValue taskResult = apiResult(fd, task.result());
                                    enforceValueLimits(taskResult);
                                    stack.push(taskResult);
                                } else {
                                    return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                            "Task " + task.state(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                                }
                            } else {
                                ScriptValue sv = apiResult(fd, result);
                                enforceValueLimits(sv);
                                stack.push(sv);
                            }
                        } catch (ResourceLimitException e) {
                            throw e;
                        } catch (Throwable t) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                    }
                    case CALL_MEMBER -> {
                        String memberName = module.constantPool().stringValue(operand0);
                        int argCount = operand1;
                        ScriptValue[] memberArgs = new ScriptValue[argCount];
                        for (int i = argCount - 1; i >= 0; i--) memberArgs[i] = stack.pop();
                        ScriptValue recv = stack.pop();
                        ScriptValue result;
                        if (recv instanceof ListValue list) {
                            result = switch (memberName) {
                                case "add" -> {
                                    list.elements().add(memberArgs[0]);
                                    try {
                                        enforceValueLimits(list);
                                        if (host != null) host.commitStateMutation(fiberId);
                                    } catch (ResourceLimitException | ResourceLimitViolation error) {
                                        list.elements().remove(list.elements().size() - 1);
                                        throw error;
                                    }
                                    yield PrimitiveValue.nullValue();
                                }
                                case "remove" -> {
                                    boolean removed = list.elements().remove(memberArgs[0]);
                                    if (removed && host != null) host.commitStateMutation(fiberId);
                                    yield PrimitiveValue.of(removed);
                                }
                                case "contains" -> PrimitiveValue.of(list.elements().contains(memberArgs[0]));
                                case "clear" -> {
                                    list.elements().clear();
                                    if (host != null) host.commitStateMutation(fiberId);
                                    yield PrimitiveValue.nullValue();
                                }
                                default -> null;
                            };
                        } else if (recv instanceof SetValue set) {
                            result = switch (memberName) {
                                case "add" -> {
                                    boolean added = set.elements().add(memberArgs[0]);
                                    try {
                                        enforceValueLimits(set);
                                        if (added && host != null) host.commitStateMutation(fiberId);
                                    } catch (ResourceLimitException | ResourceLimitViolation error) {
                                        if (added) set.elements().remove(memberArgs[0]);
                                        throw error;
                                    }
                                    yield PrimitiveValue.nullValue();
                                }
                                case "remove" -> {
                                    boolean removed = set.elements().remove(memberArgs[0]);
                                    if (removed && host != null) host.commitStateMutation(fiberId);
                                    yield PrimitiveValue.of(removed);
                                }
                                case "contains" -> PrimitiveValue.of(set.elements().contains(memberArgs[0]));
                                case "clear" -> {
                                    set.elements().clear();
                                    if (host != null) host.commitStateMutation(fiberId);
                                    yield PrimitiveValue.nullValue();
                                }
                                default -> null;
                            };
                        } else if (recv instanceof MapValue map) {
                            result = switch (memberName) {
                                case "put" -> {
                                    boolean hadKey = map.entries().containsKey(memberArgs[0]);
                                    ScriptValue previous = map.entries().put(memberArgs[0], memberArgs[1]);
                                    try {
                                        enforceValueLimits(map);
                                        if (host != null) host.commitStateMutation(fiberId);
                                    } catch (ResourceLimitException | ResourceLimitViolation error) {
                                        if (hadKey) map.entries().put(memberArgs[0], previous);
                                        else map.entries().remove(memberArgs[0]);
                                        throw error;
                                    }
                                    yield PrimitiveValue.nullValue();
                                }
                                case "remove" -> {
                                    boolean removed = map.entries().remove(memberArgs[0]) != null;
                                    if (removed && host != null) host.commitStateMutation(fiberId);
                                    yield PrimitiveValue.of(removed);
                                }
                                case "containsKey" -> PrimitiveValue.of(map.entries().containsKey(memberArgs[0]));
                                case "clear" -> {
                                    map.entries().clear();
                                    if (host != null) host.commitStateMutation(fiberId);
                                    yield PrimitiveValue.nullValue();
                                }
                                default -> null;
                            };
                        } else {
                            result = null;
                        }
                        if (result == null) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "Method not found: " + memberName, currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        stack.push(result);
                    }
                    case GET_MEMBER -> {
                        String memberName = module.constantPool().stringValue(operand0);
                        ScriptValue recv = stack.pop();
                        if (recv instanceof StringValue sv && memberName.equals("length")) {
                            stack.push(PrimitiveValue.of(sv.value().length()));
                        } else if (recv instanceof ListValue lv && memberName.equals("size")) {
                            stack.push(PrimitiveValue.of(lv.elements().size()));
                        } else if (recv instanceof ListValue lv && memberName.equals("isEmpty")) {
                            stack.push(PrimitiveValue.of(lv.elements().isEmpty()));
                        } else if (recv instanceof ListValue lv && vectorMemberIndex(memberName) >= 0 && vectorMemberIndex(memberName) < lv.elements().size()) {
                            stack.push(lv.elements().get(vectorMemberIndex(memberName)));
                        } else if (recv instanceof MapValue mv && memberName.equals("size")) {
                            stack.push(PrimitiveValue.of(mv.entries().size()));
                        } else if (recv instanceof MapValue mv && memberName.equals("isEmpty")) {
                            stack.push(PrimitiveValue.of(mv.entries().isEmpty()));
                        } else if (recv instanceof SetValue sv && memberName.equals("size")) {
                            stack.push(PrimitiveValue.of(sv.elements().size()));
                        } else if (recv instanceof SetValue sv && memberName.equals("isEmpty")) {
                            stack.push(PrimitiveValue.of(sv.elements().isEmpty()));
                        } else if (recv instanceof StructValue struct) {
                            stack.push(struct.fields().getOrDefault(memberName, PrimitiveValue.nullValue()));
                        } else {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "Member not found: " + memberName, currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                    }
                    case SET_MEMBER -> {
                        stack.pop();
                        stack.pop();
                        return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                "Member assignment is not supported for immutable script values", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                    }
                    case CREATE_LIST -> {
                        int count = operand0;
                        List<ScriptValue> elements = new ArrayList<>();
                        for (int i = 0; i < count; i++) elements.add(stack.pop());
                        Collections.reverse(elements);
                        ScriptValue value = new ListValue(elements);
                        enforceValueLimits(value);
                        stack.push(value);
                    }
                    case CREATE_SET -> {
                        int count = operand0;
                        Set<ScriptValue> elements = new LinkedHashSet<>();
                        for (int i = 0; i < count; i++) elements.add(stack.pop());
                        ScriptValue value = new SetValue(elements);
                        enforceValueLimits(value);
                        stack.push(value);
                    }
                    case CREATE_MAP -> {
                        int count = operand0;
                        Map<ScriptValue, ScriptValue> entries = new LinkedHashMap<>();
                        for (int i = 0; i < count; i++) {
                            ScriptValue v = stack.pop();
                            ScriptValue k = stack.pop();
                            entries.put(k, v);
                        }
                        ScriptValue value = new MapValue(entries);
                        enforceValueLimits(value);
                        stack.push(value);
                    }
                    case GET_INDEX -> {
                        ScriptValue idx = stack.pop();
                        ScriptValue recv = stack.pop();
                        if (recv instanceof ListValue list && idx instanceof PrimitiveValue.IntV index) {
                            int i = index.value();
                            if (i < 0 || i >= list.elements().size()) throw new IndexAccessException("List index out of bounds: " + i);
                            stack.push(list.elements().get(i));
                        } else if (recv instanceof SetValue set && idx instanceof PrimitiveValue.IntV index) {
                            int i = index.value();
                            if (i < 0 || i >= set.elements().size()) throw new IndexAccessException("Set index out of bounds: " + i);
                            stack.push(set.elements().stream().skip(i).findFirst().orElseThrow());
                        } else if (recv instanceof StringValue string && idx instanceof PrimitiveValue.IntV index) {
                            int i = index.value();
                            if (i < 0 || i >= string.value().length()) throw new IndexAccessException("String index out of bounds: " + i);
                            stack.push(PrimitiveValue.of(string.value().charAt(i)));
                        } else if (recv instanceof MapValue map) {
                            if (!map.entries().containsKey(idx)) throw new IndexAccessException("Map key not found: " + valueToString(idx));
                            stack.push(map.entries().get(idx));
                        } else {
                            throw new IndexAccessException("Value is not indexable");
                        }
                    }
                    case SPAWN -> {
                        int funcIdx = operand0;
                        int ac = operand1;
                        ScriptValue[] spawnArgs = new ScriptValue[ac];
                        for (int i = ac - 1; i >= 0; i--) {
                            spawnArgs[i] = stack.pop();
                        }
                        if (host != null) {
                            long childId = host.spawnFiber(module.scriptId(), funcIdx, spawnArgs, fiberId);
                            if (childId == -1) {
                                stack.push(new TaskValue(-1, io.velora.api.task.TaskState.FAILED));
                            } else {
                                stack.push(new TaskValue(childId, io.velora.api.task.TaskState.PENDING));
                            }
                        } else {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "spawn requires a scheduler", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                    }
                    case AWAIT -> {
                        ScriptValue task = stack.pop();
                        if (!(task instanceof TaskValue tv)) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "await requires Task<T>", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        if (tv.state() == TaskState.FAILED || tv.taskId() < 0) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "Task failed before await", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        if (host == null) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "await requires a scheduler", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        host.awaitFiber(fiberId, tv.taskId());
                        return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.AWAIT,
                                tv.taskId(), instructions, System.nanoTime() - startTime);
                    }
                    case DELAY -> {
                        ScriptValue d = stack.pop();
                        long nanos;
                        if (d instanceof PrimitiveValue.LongV lv) nanos = lv.value();
                        else if (d instanceof PrimitiveValue.IntV iv) nanos = iv.value();
                        else if (d instanceof PrimitiveValue.FloatV fv) nanos = (long) fv.value();
                        else if (d instanceof PrimitiveValue.DoubleV dv) nanos = (long) dv.value();
                        else {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "delay requires Duration or a nanosecond number", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        if (nanos < 0) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "delay cannot be negative", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        if (host == null) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "delay requires a scheduler", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        host.sleepFiber(fiberId, host.nanoTime() + nanos);
                        return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.SLEEP,
                                nanos, instructions, System.nanoTime() - startTime);
                    }
                    case YIELD -> {
                        if (host == null) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "yield requires a scheduler", currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
                        }
                        host.yieldFiber(fiberId);
                        return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.YIELD,
                                0, instructions, System.nanoTime() - startTime);
                    }
                    case CHECK_CANCELLED -> {
                        if (host != null && host.isCancelled(fiberId)) {
                            return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.CANCELLED,
                                    0, instructions, System.nanoTime() - startTime);
                        }
                    }
                    case LINE -> frame.line(operand0);
                    case BREAKPOINT -> {}
                }
            }

            return VmExecutionResult.success(PrimitiveValue.nullValue(), instructions, System.nanoTime() - startTime);

        } catch (ResourceLimitException | ResourceLimitViolation e) {
            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_RESOURCE_LIMIT,
                    e.getMessage(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
        } catch (IndexAccessException e) {
            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_INDEX_OUT_OF_BOUNDS,
                    e.getMessage(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
        } catch (ArithmeticOverflowException e) {
            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_ARITHMETIC_OVERFLOW,
                    e.getMessage(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
        } catch (ArithmeticException e) {
            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_DIVISION_BY_ZERO,
                    e.getMessage(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
        } catch (Throwable t) {
            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(), currentLine(callStack), fiberId), instructions, System.nanoTime() - startTime);
        }
    }


    private int currentLine(Deque<CallFrame> callStack) {
        CallFrame frame = callStack.peek();
        return frame != null ? frame.line() : 0;
    }

    private ScriptValue constToValue(ConstantPool pool, int index, ConstantPool.Tag tag) {
        return switch (tag) {
            case INT -> PrimitiveValue.of(pool.intValue(index));
            case LONG -> PrimitiveValue.of(pool.longValue(index));
            case FLOAT -> PrimitiveValue.of(pool.floatValue(index));
            case DOUBLE -> PrimitiveValue.of(pool.doubleValue(index));
            case STRING -> new StringValue(pool.stringValue(index));
            case BOOLEAN -> PrimitiveValue.of(pool.booleanValue(index));
            case DURATION -> PrimitiveValue.of(pool.durationNanos(index));
            case NULL -> PrimitiveValue.nullValue();
        };
    }

    public static ScriptValue copyValue(ScriptValue value) {
        if (value instanceof ListValue list) {
            List<ScriptValue> elements = new ArrayList<>(list.elements().size());
            for (ScriptValue element : list.elements()) elements.add(copyValue(element));
            return new ListValue(elements);
        }
        if (value instanceof SetValue set) {
            Set<ScriptValue> elements = new LinkedHashSet<>();
            for (ScriptValue element : set.elements()) elements.add(copyValue(element));
            return new SetValue(elements);
        }
        if (value instanceof MapValue map) {
            Map<ScriptValue, ScriptValue> entries = new LinkedHashMap<>();
            for (var entry : map.entries().entrySet()) entries.put(copyValue(entry.getKey()), copyValue(entry.getValue()));
            return new MapValue(entries);
        }
        if (value instanceof StructValue struct) {
            Map<String, ScriptValue> fields = new LinkedHashMap<>();
            for (var entry : struct.fields().entrySet()) fields.put(entry.getKey(), copyValue(entry.getValue()));
            return new StructValue(struct.typeName(), fields, struct.hostValue());
        }
        return value;
    }

    public static ScriptValue javaToValue(Object o) {
        return javaToValue(o, new IdentityHashMap<>());
    }

    private static ScriptValue javaToValue(Object o, IdentityHashMap<Object, Boolean> active) {
        if (o == null) return PrimitiveValue.nullValue();
        if (o instanceof ScriptValue value) return value;
        if (o instanceof Integer i) return PrimitiveValue.of(i);
        if (o instanceof Long l) return PrimitiveValue.of(l);
        if (o instanceof Float f) return PrimitiveValue.of(f);
        if (o instanceof Double d) return PrimitiveValue.of(d);
        if (o instanceof Boolean b) return PrimitiveValue.of(b);
        if (o instanceof Byte b) return PrimitiveValue.of(b);
        if (o instanceof Character c) return PrimitiveValue.of(c);
        if (o instanceof String string) return new StringValue(string);
        if (o instanceof List<?> list) {
            enterConversion(list, active);
            try {
                List<ScriptValue> elements = new ArrayList<>(list.size());
                for (Object element : list) elements.add(javaToValue(element, active));
                return new ListValue(elements);
            } finally { active.remove(list); }
        }
        if (o instanceof Map<?, ?> map) {
            enterConversion(map, active);
            try {
                Map<ScriptValue, ScriptValue> entries = new LinkedHashMap<>();
                for (var entry : map.entrySet()) entries.put(javaToValue(entry.getKey(), active), javaToValue(entry.getValue(), active));
                return new MapValue(entries);
            } finally { active.remove(map); }
        }
        if (o instanceof Set<?> set) {
            enterConversion(set, active);
            try {
                Set<ScriptValue> elements = new LinkedHashSet<>();
                for (Object element : set) elements.add(javaToValue(element, active));
                return new SetValue(elements);
            } finally { active.remove(set); }
        }
        return new HandleValue(o.getClass().getSimpleName(), o);
    }

    private ScriptValue apiResult(FunctionDescriptor descriptor, Object result) {
        return javaToValue(descriptor.returnType(), result);
    }

    public static ScriptValue javaToValue(io.velora.api.type.VeloraType type, Object value) {
        return javaToValue(type, value, new IdentityHashMap<>());
    }

    private static ScriptValue javaToValue(io.velora.api.type.VeloraType type, Object value, IdentityHashMap<Object, Boolean> active) {
        io.velora.api.type.VeloraType expected = type.nonNull();
        if (value == null) {
            if (expected == io.velora.api.type.VeloraTypes.UNIT || type.isNullable()) return PrimitiveValue.nullValue();
            throw new IllegalArgumentException("Host returned null for non-null type " + type.name());
        }
        if (value instanceof ScriptValue scriptValue) return validateScriptValue(type, scriptValue, new IdentityHashMap<>());
        io.velora.api.type.VeloraType listElement = io.velora.api.type.VeloraTypes.listElement(expected);
        if (listElement != null) {
            if (!(value instanceof List<?> list)) throw typeMismatch(expected, value);
            List<ScriptValue> elements = new ArrayList<>(list.size());
            enterConversion(list, active);
            try {
                for (Object element : list) elements.add(javaToValue(listElement, element, active));
            } finally { active.remove(list); }
            return new ListValue(elements);
        }
        io.velora.api.type.VeloraType setElement = io.velora.api.type.VeloraTypes.setElement(expected);
        if (setElement != null) {
            if (!(value instanceof Set<?> set)) throw typeMismatch(expected, value);
            Set<ScriptValue> elements = new LinkedHashSet<>();
            enterConversion(set, active);
            try {
                for (Object element : set) elements.add(javaToValue(setElement, element, active));
            } finally { active.remove(set); }
            return new SetValue(elements);
        }
        io.velora.api.type.VeloraType mapKey = io.velora.api.type.VeloraTypes.mapKey(expected);
        io.velora.api.type.VeloraType mapValue = io.velora.api.type.VeloraTypes.mapValue(expected);
        if (mapKey != null && mapValue != null) {
            if (!(value instanceof Map<?, ?> map)) throw typeMismatch(expected, value);
            Map<ScriptValue, ScriptValue> entries = new LinkedHashMap<>();
            enterConversion(map, active);
            try {
                for (var entry : map.entrySet()) entries.put(javaToValue(mapKey, entry.getKey(), active), javaToValue(mapValue, entry.getValue(), active));
            } finally { active.remove(map); }
            return new MapValue(entries);
        }
        if (expected instanceof io.velora.api.type.HandleType) {
            Class<?> javaClass = expected.javaClass();
            if (!javaClass.isInstance(value)) throw new IllegalArgumentException("Handle type mismatch: expected " + expected.name() + " (" + javaClass.getTypeName() + "), got " + value.getClass().getTypeName());
            return new HandleValue(expected.name(), value);
        }
        if (expected instanceof io.velora.api.type.StructType struct) {
            if (!struct.javaClass().isInstance(value)) throw typeMismatch(expected, value);
            enterConversion(value, active);
            try {
                Map<String, ScriptValue> fields = new LinkedHashMap<>();
                for (io.velora.api.type.StructType.Property property : struct.properties()) {
                    fields.put(property.name(), javaToValue(property.type(), property.accessor().apply(value), active));
                }
                return new StructValue(expected.name(), fields, value);
            } finally { active.remove(value); }
        }
        if (expected instanceof io.velora.api.type.EnumType enumType) {
            if (!enumType.javaClass().isInstance(value)) throw typeMismatch(expected, value);
            for (int i = 0; i < enumType.constants().size(); i++) {
                io.velora.api.type.EnumType.Constant constant = enumType.constants().get(i);
                if (Objects.equals(constant.value(), value) || value instanceof Enum<?> javaEnum && javaEnum.name().equals(constant.name())) {
                    return new EnumValue(expected.name(), constant.name(), i, value);
                }
            }
            throw new IllegalArgumentException("Unknown enum value for " + expected.name() + ": " + value);
        }
        if (expected == io.velora.api.type.VeloraTypes.UNIT || expected == io.velora.api.type.VeloraTypes.NOTHING) throw typeMismatch(expected, value);
        if (expected == io.velora.api.type.VeloraTypes.BYTE && value instanceof Byte number) return PrimitiveValue.of(number);
        if (expected == io.velora.api.type.VeloraTypes.INT && (value instanceof Byte || value instanceof Short || value instanceof Integer)) return PrimitiveValue.of(((Number) value).intValue());
        if (expected == io.velora.api.type.VeloraTypes.LONG && (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) return PrimitiveValue.of(((Number) value).longValue());
        if (expected == io.velora.api.type.VeloraTypes.FLOAT && (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Float)) return PrimitiveValue.of(((Number) value).floatValue());
        if (expected == io.velora.api.type.VeloraTypes.DOUBLE && value instanceof Number number) return PrimitiveValue.of(number.doubleValue());
        if (expected == io.velora.api.type.VeloraTypes.BOOLEAN && value instanceof Boolean bool) return PrimitiveValue.of(bool);
        if (expected == io.velora.api.type.VeloraTypes.CHAR && value instanceof Character character) return PrimitiveValue.of(character);
        if (expected == io.velora.api.type.VeloraTypes.STRING && value instanceof String string) return new StringValue(string);
        if (expected == io.velora.api.type.VeloraTypes.DURATION && value instanceof java.time.Duration duration) return PrimitiveValue.of(duration.toNanos());
        if (expected == io.velora.api.type.VeloraTypes.DURATION && (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) return PrimitiveValue.of(((Number) value).longValue());
        if (expected == io.velora.api.type.VeloraTypes.UUID && value instanceof java.util.UUID uuid) return new StringValue(uuid.toString());
        if (expected == io.velora.api.type.VeloraTypes.UUID && value instanceof String string) {
            java.util.UUID.fromString(string);
            return new StringValue(string);
        }
        if ((expected == io.velora.api.type.VeloraTypes.VEC2 || expected == io.velora.api.type.VeloraTypes.VEC3) && value instanceof double[] array) {
            int size = expected == io.velora.api.type.VeloraTypes.VEC2 ? 2 : 3;
            if (array.length != size) throw new IllegalArgumentException(expected.name() + " requires exactly " + size + " components");
            List<ScriptValue> elements = new ArrayList<>(array.length);
            for (double element : array) elements.add(PrimitiveValue.of(element));
            return new ListValue(elements);
        }
        if (expected == io.velora.api.type.VeloraTypes.COLOR && value instanceof int[] array) {
            if (array.length != 4) throw new IllegalArgumentException("Color requires exactly 4 components");
            List<ScriptValue> elements = new ArrayList<>(array.length);
            for (int element : array) elements.add(PrimitiveValue.of(element));
            return new ListValue(elements);
        }
        throw typeMismatch(expected, value);
    }

    private static ScriptValue validateScriptValue(io.velora.api.type.VeloraType type, ScriptValue value, IdentityHashMap<Object, Boolean> active) {
        io.velora.api.type.VeloraType expected = type.nonNull();
        if (value == null || value.isNull()) {
            if (expected == io.velora.api.type.VeloraTypes.UNIT || type.isNullable()) return PrimitiveValue.nullValue();
            throw new IllegalArgumentException("Host returned null for non-null type " + type.name());
        }
        io.velora.api.type.VeloraType listElement = io.velora.api.type.VeloraTypes.listElement(expected);
        if (listElement != null) {
            if (!(value instanceof ListValue list)) throw scriptValueMismatch(expected, value);
            enterConversion(list, active);
            try { for (ScriptValue element : list.elements()) validateScriptValue(listElement, element, active); }
            finally { active.remove(list); }
            return value;
        }
        io.velora.api.type.VeloraType setElement = io.velora.api.type.VeloraTypes.setElement(expected);
        if (setElement != null) {
            if (!(value instanceof SetValue set)) throw scriptValueMismatch(expected, value);
            enterConversion(set, active);
            try { for (ScriptValue element : set.elements()) validateScriptValue(setElement, element, active); }
            finally { active.remove(set); }
            return value;
        }
        io.velora.api.type.VeloraType mapKey = io.velora.api.type.VeloraTypes.mapKey(expected);
        io.velora.api.type.VeloraType mapValue = io.velora.api.type.VeloraTypes.mapValue(expected);
        if (mapKey != null && mapValue != null) {
            if (!(value instanceof MapValue map)) throw scriptValueMismatch(expected, value);
            enterConversion(map, active);
            try {
                for (var entry : map.entries().entrySet()) {
                    validateScriptValue(mapKey, entry.getKey(), active);
                    validateScriptValue(mapValue, entry.getValue(), active);
                }
            } finally { active.remove(map); }
            return value;
        }
        if (expected instanceof io.velora.api.type.HandleType) {
            if (!(value instanceof HandleValue handle) || !handle.typeName().equals(expected.name()) || !expected.javaClass().isInstance(handle.handle())) throw scriptValueMismatch(expected, value);
            return value;
        }
        if (expected instanceof io.velora.api.type.StructType structType) {
            if (!(value instanceof StructValue struct) || !struct.typeName().equals(expected.name()) || struct.fields().size() != structType.properties().size()) throw scriptValueMismatch(expected, value);
            enterConversion(struct, active);
            try {
                for (io.velora.api.type.StructType.Property property : structType.properties()) {
                    ScriptValue field = struct.fields().get(property.name());
                    if (field == null) throw scriptValueMismatch(expected, value);
                    validateScriptValue(property.type(), field, active);
                }
            } finally { active.remove(struct); }
            if (struct.hostValue() != null && !structType.javaClass().isInstance(struct.hostValue())) throw scriptValueMismatch(expected, value);
            return value;
        }
        if (expected instanceof io.velora.api.type.EnumType enumType) {
            if (!(value instanceof EnumValue enumValue) || !enumValue.enumName().equals(expected.name())) throw scriptValueMismatch(expected, value);
            io.velora.api.type.EnumType.Constant constant = enumType.constant(enumValue.constantName());
            if (constant == null || enumType.constants().indexOf(constant) != enumValue.ordinal()) throw scriptValueMismatch(expected, value);
            if (enumValue.hostValue() != null && constant.value() != null && !Objects.equals(constant.value(), enumValue.hostValue())) throw scriptValueMismatch(expected, value);
            return value;
        }
        boolean matches = expected == io.velora.api.type.VeloraTypes.BOOLEAN && value instanceof PrimitiveValue.BooleanV
                || expected == io.velora.api.type.VeloraTypes.BYTE && value instanceof PrimitiveValue.ByteV
                || expected == io.velora.api.type.VeloraTypes.INT && value instanceof PrimitiveValue.IntV
                || (expected == io.velora.api.type.VeloraTypes.LONG || expected == io.velora.api.type.VeloraTypes.DURATION) && value instanceof PrimitiveValue.LongV
                || expected == io.velora.api.type.VeloraTypes.FLOAT && value instanceof PrimitiveValue.FloatV
                || expected == io.velora.api.type.VeloraTypes.DOUBLE && value instanceof PrimitiveValue.DoubleV
                || expected == io.velora.api.type.VeloraTypes.CHAR && value instanceof PrimitiveValue.CharV
                || expected == io.velora.api.type.VeloraTypes.STRING && value instanceof StringValue
                || expected == io.velora.api.type.VeloraTypes.UUID && value instanceof StringValue string && validUuid(string.value())
                || expected == io.velora.api.type.VeloraTypes.VEC2 && vectorValue(value, 2)
                || expected == io.velora.api.type.VeloraTypes.VEC3 && vectorValue(value, 3)
                || expected == io.velora.api.type.VeloraTypes.COLOR && colorValue(value);
        if (!matches) throw scriptValueMismatch(expected, value);
        return value;
    }

    private static boolean validUuid(String value) {
        try { java.util.UUID.fromString(value); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static boolean vectorValue(ScriptValue value, int size) {
        if (!(value instanceof ListValue list) || list.elements().size() != size) return false;
        for (ScriptValue element : list.elements()) if (!(element instanceof PrimitiveValue.DoubleV)) return false;
        return true;
    }

    private static boolean colorValue(ScriptValue value) {
        if (!(value instanceof ListValue list) || list.elements().size() != 4) return false;
        for (ScriptValue element : list.elements()) if (!(element instanceof PrimitiveValue.IntV)) return false;
        return true;
    }

    private static IllegalArgumentException scriptValueMismatch(io.velora.api.type.VeloraType expected, ScriptValue value) {
        return new IllegalArgumentException("Host ScriptValue type mismatch: expected " + expected.name() + ", got " + value.getClass().getSimpleName());
    }

    private static void enterConversion(Object value, IdentityHashMap<Object, Boolean> active) {
        if (active.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic host values are not supported");
    }

    private static IllegalArgumentException typeMismatch(io.velora.api.type.VeloraType expected, Object value) {
        return new IllegalArgumentException("Host value type mismatch: expected " + expected.name() + " (" + expected.javaClass().getTypeName() + "), got " + value.getClass().getTypeName());
    }

    private boolean matchesType(ScriptValue value, String requestedType) {
        boolean nullable = requestedType.endsWith("?");
        String type = nullable ? requestedType.substring(0, requestedType.length() - 1) : requestedType;
        if (value == null || value.isNull()) return nullable;
        if (type.startsWith("List<")) return value instanceof ListValue;
        if (type.startsWith("Set<")) return value instanceof SetValue;
        if (type.startsWith("Map<")) return value instanceof MapValue;
        if (type.startsWith("Task<")) return value instanceof TaskValue;
        return switch (type) {
            case "Boolean" -> value instanceof PrimitiveValue.BooleanV;
            case "Byte" -> value instanceof PrimitiveValue.ByteV;
            case "Int" -> value instanceof PrimitiveValue.IntV;
            case "Long", "Duration" -> value instanceof PrimitiveValue.LongV;
            case "Float" -> value instanceof PrimitiveValue.FloatV;
            case "Double" -> value instanceof PrimitiveValue.DoubleV;
            case "Char" -> value instanceof PrimitiveValue.CharV;
            case "String" -> value instanceof StringValue;
            case "UUID" -> value instanceof StringValue string && validUuid(string.value());
            case "Vec2" -> vectorValue(value, 2);
            case "Vec3" -> vectorValue(value, 3);
            case "Color" -> colorValue(value);
            default -> value instanceof HandleValue handle && handle.typeName().equals(type)
                    || value instanceof StructValue struct && struct.typeName().equals(type)
                    || value instanceof EnumValue enumValue && enumValue.enumName().equals(type);
        };
    }

    private int vectorMemberIndex(String member) {
        return switch (member) {
            case "x", "r" -> 0;
            case "y", "g" -> 1;
            case "z", "b" -> 2;
            case "a" -> 3;
            default -> -1;
        };
    }

    private boolean toBoolean(ScriptValue v) {
        if (v instanceof PrimitiveValue.BooleanV b) return b.value();
        if (v instanceof PrimitiveValue.NullV) return false;
        return v != null && !v.isNull();
    }

    private boolean equal(ScriptValue a, ScriptValue b) {
        if (a.isNull() && b.isNull()) return true;
        if (a.isNull() || b.isNull()) return false;
        if (a.boxed() instanceof Number left && b.boxed() instanceof Number right) {
            if (left instanceof Float || left instanceof Double || right instanceof Float || right instanceof Double) return Double.compare(left.doubleValue(), right.doubleValue()) == 0;
            return left.longValue() == right.longValue();
        }
        return a.boxed().equals(b.boxed());
    }

    private int compare(ScriptValue a, ScriptValue b) {
        if (a.boxed() instanceof Number left && b.boxed() instanceof Number right) {
            if (left instanceof Float || left instanceof Double || right instanceof Float || right instanceof Double) return Double.compare(left.doubleValue(), right.doubleValue());
            return Long.compare(left.longValue(), right.longValue());
        }
        if (a instanceof StringValue left && b instanceof StringValue right) return left.value().compareTo(right.value());
        throw new IllegalArgumentException("Values are not comparable");
    }

    private ScriptValue add(ScriptValue a, ScriptValue b) {
        if (a instanceof StringValue || b instanceof StringValue) {
            ScriptValue value = new StringValue(valueToString(a) + valueToString(b));
            enforceValueLimits(value);
            return value;
        }
        return numeric(a, b, '+');
    }

    private String valueToString(ScriptValue value) {
        if (value instanceof StringValue string) return string.value();
        if (value == null || value.isNull()) return "null";
        Object boxed = value.boxed();
        return boxed != null ? boxed.toString() : "null";
    }

    private ScriptValue sub(ScriptValue a, ScriptValue b) { return numeric(a, b, '-'); }
    private ScriptValue mul(ScriptValue a, ScriptValue b) { return numeric(a, b, '*'); }
    private ScriptValue div(ScriptValue a, ScriptValue b) { return numeric(a, b, '/'); }
    private ScriptValue mod(ScriptValue a, ScriptValue b) { return numeric(a, b, '%'); }

    private ScriptValue numeric(ScriptValue a, ScriptValue b, char op) {
        if (!(a.boxed() instanceof Number left) || !(b.boxed() instanceof Number right)) throw new IllegalArgumentException("Numeric operator requires numbers");
        if ((op == '/' || op == '%') && right.doubleValue() == 0.0d) throw new ArithmeticException(op == '/' ? "Division by zero" : "Modulo by zero");
        if (a instanceof PrimitiveValue.DoubleV || b instanceof PrimitiveValue.DoubleV) {
            double x = left.doubleValue(), y = right.doubleValue();
            return PrimitiveValue.of(switch (op) { case '+' -> x + y; case '-' -> x - y; case '*' -> x * y; case '/' -> x / y; case '%' -> x % y; default -> throw new IllegalArgumentException(); });
        }
        if (a instanceof PrimitiveValue.FloatV || b instanceof PrimitiveValue.FloatV) {
            float x = left.floatValue(), y = right.floatValue();
            return PrimitiveValue.of(switch (op) { case '+' -> x + y; case '-' -> x - y; case '*' -> x * y; case '/' -> x / y; case '%' -> x % y; default -> throw new IllegalArgumentException(); });
        }
        if (a instanceof PrimitiveValue.LongV || b instanceof PrimitiveValue.LongV) {
            long x = left.longValue(), y = right.longValue();
            try {
                return PrimitiveValue.of(switch (op) {
                    case '+' -> Math.addExact(x, y);
                    case '-' -> Math.subtractExact(x, y);
                    case '*' -> Math.multiplyExact(x, y);
                    case '/' -> { if (x == Long.MIN_VALUE && y == -1) throw new ArithmeticException("long overflow"); yield x / y; }
                    case '%' -> x % y;
                    default -> throw new IllegalArgumentException();
                });
            } catch (ArithmeticException error) {
                if ((op == '/' || op == '%') && y == 0) throw error;
                throw new ArithmeticOverflowException("Long arithmetic overflow", error);
            }
        }
        int x = left.intValue(), y = right.intValue();
        try {
            return PrimitiveValue.of(switch (op) {
                case '+' -> Math.addExact(x, y);
                case '-' -> Math.subtractExact(x, y);
                case '*' -> Math.multiplyExact(x, y);
                case '/' -> { if (x == Integer.MIN_VALUE && y == -1) throw new ArithmeticException("int overflow"); yield x / y; }
                case '%' -> x % y;
                default -> throw new IllegalArgumentException();
            });
        } catch (ArithmeticException error) {
            if ((op == '/' || op == '%') && y == 0) throw error;
            throw new ArithmeticOverflowException("Int arithmetic overflow", error);
        }
    }

    private ScriptValue negate(ScriptValue value) {
        if (value instanceof PrimitiveValue.IntV v) {
            try { return PrimitiveValue.of(Math.negateExact(v.value())); }
            catch (ArithmeticException error) { throw new ArithmeticOverflowException("Int arithmetic overflow", error); }
        }
        if (value instanceof PrimitiveValue.LongV v) {
            try { return PrimitiveValue.of(Math.negateExact(v.value())); }
            catch (ArithmeticException error) { throw new ArithmeticOverflowException("Long arithmetic overflow", error); }
        }
        if (value instanceof PrimitiveValue.FloatV v) return PrimitiveValue.of(-v.value());
        if (value instanceof PrimitiveValue.DoubleV v) return PrimitiveValue.of(-v.value());
        if (value instanceof PrimitiveValue.ByteV v) return PrimitiveValue.of(-v.value());
        throw new IllegalArgumentException("Unary minus requires a number");
    }

    private void enforceValueLimits(ScriptValue value) {
        validateValue(value, 0, new IdentityHashMap<>());
    }

    private void validateValue(ScriptValue value, int depth, IdentityHashMap<ScriptValue, Boolean> active) {
        if (depth > maxCollectionDepth) throw new ResourceLimitException("Collection depth limit exceeded");
        if (value instanceof StringValue string) {
            if (string.value().length() > maxStringLength) throw new ResourceLimitException("String length limit exceeded");
            return;
        }
        if (!(value instanceof ListValue || value instanceof MapValue || value instanceof SetValue || value instanceof StructValue)) return;
        if (active.put(value, Boolean.TRUE) != null) throw new ResourceLimitException("Cyclic values are not supported");
        try {
            if (value instanceof ListValue list) {
                if (list.elements().size() > maxCollectionElements) throw new ResourceLimitException("Collection element limit exceeded");
                for (ScriptValue element : list.elements()) validateValue(element, depth + 1, active);
            } else if (value instanceof MapValue map) {
                if (map.entries().size() > maxCollectionElements) throw new ResourceLimitException("Collection element limit exceeded");
                for (var entry : map.entries().entrySet()) {
                    validateValue(entry.getKey(), depth + 1, active);
                    validateValue(entry.getValue(), depth + 1, active);
                }
            } else if (value instanceof SetValue set) {
                if (set.elements().size() > maxCollectionElements) throw new ResourceLimitException("Collection element limit exceeded");
                for (ScriptValue element : set.elements()) validateValue(element, depth + 1, active);
            } else if (value instanceof StructValue struct) {
                if (struct.fields().size() > maxCollectionElements) throw new ResourceLimitException("Collection element limit exceeded");
                for (ScriptValue field : struct.fields().values()) validateValue(field, depth + 1, active);
            }
        } finally { active.remove(value); }
    }

    private static final class IndexAccessException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private IndexAccessException(String message) { super(message); }
    }

    private static final class ArithmeticOverflowException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private ArithmeticOverflowException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class ResourceLimitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ResourceLimitException(String message) { super(message); }
    }

    private record SimpleFunctionContext(FunctionDescriptor descriptor, Object[] args, String scriptId, long fiberId) implements FunctionContext {
        @Override public <T> T argument(String name, Class<T> type) { return argument(argumentIndex(name), type); }
        @Override @SuppressWarnings("unchecked") public <T> T argument(int index, Class<T> type) {
            Objects.requireNonNull(type, "type");
            Object value = argument(index);
            if (value == null) return null;
            if (type == byte.class || type == Byte.class) {
                if (value instanceof Byte number) return (T) number;
                throw new IllegalArgumentException("Function argument cannot be narrowed to Byte");
            }
            if (type == short.class || type == Short.class) {
                if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
                    int number = ((Number) value).intValue();
                    if (number >= Short.MIN_VALUE && number <= Short.MAX_VALUE) return (T) Short.valueOf((short) number);
                }
                throw new IllegalArgumentException("Function argument cannot be narrowed to Short");
            }
            if (type == int.class || type == Integer.class) {
                if (value instanceof Byte || value instanceof Short || value instanceof Integer) return (T) Integer.valueOf(((Number) value).intValue());
                throw new IllegalArgumentException("Function argument cannot be narrowed to Int");
            }
            if (type == long.class || type == Long.class) {
                if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return (T) Long.valueOf(((Number) value).longValue());
                throw new IllegalArgumentException("Function argument cannot be converted to Long");
            }
            if (type == float.class || type == Float.class) {
                if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Float) return (T) Float.valueOf(((Number) value).floatValue());
                throw new IllegalArgumentException("Function argument cannot be converted to Float");
            }
            if (type == double.class || type == Double.class) {
                if (value instanceof Number number) return (T) Double.valueOf(number.doubleValue());
                throw new IllegalArgumentException("Function argument cannot be converted to Double");
            }
            if (type == boolean.class || type == Boolean.class) return (T) Boolean.valueOf((Boolean) value);
            if (type == char.class || type == Character.class) return (T) Character.valueOf((Character) value);
            if (type == java.time.Duration.class && value instanceof Number number) return (T) java.time.Duration.ofNanos(number.longValue());
            if (type == java.util.UUID.class && value instanceof String string) return (T) java.util.UUID.fromString(string);
            if (type == double[].class && value instanceof java.util.List<?> list) {
                double[] array = new double[list.size()];
                for (int i = 0; i < list.size(); i++) array[i] = ((Number) list.get(i)).doubleValue();
                return (T) array;
            }
            if (type == int[].class && value instanceof java.util.List<?> list) {
                int[] array = new int[list.size()];
                for (int i = 0; i < list.size(); i++) array[i] = ((Number) list.get(i)).intValue();
                return (T) array;
            }
            return type.cast(value);
        }
        @Override public Object argument(String name) { return argument(argumentIndex(name)); }
        private int argumentIndex(String name) {
            if (descriptor != null) {
                for (int i = 0; i < descriptor.parameters().size(); i++) if (descriptor.parameters().get(i).name().equals(name)) return i;
            }
            throw new IllegalArgumentException("Unknown function argument: " + name);
        }
        @Override public Object argument(int index) {
            if (index < 0 || index >= args.length) throw new IndexOutOfBoundsException("Function argument index: " + index);
            return args[index];
        }
        @Override public int argumentCount() { return args.length; }
        @Override public String scriptId() { return scriptId; }
        @Override public long fiberId() { return fiberId; }
    }
}
