package io.velora.internal.vm;

import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionContext;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.function.FunctionInvoker;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.task.TaskState;
import io.velora.api.task.VeloraTask;
import io.velora.internal.bytecode.*;

import java.util.*;

public final class VirtualMachine {

    private final ApiRegistry apiRegistry;
    private final List<SettingDescriptor> settings;
    private final io.velora.internal.setting.SettingStore settingStore;
    private final int instructionLimit;
    private final int maxCallDepth;
    private final int maxStringLength;
    private final Map<Integer, ScriptValue> staticFields = new HashMap<>();
    private final Map<Integer, ScriptValue> instanceFields = new HashMap<>();
    private CompiledModule currentModule;

    public VirtualMachine(ApiRegistry apiRegistry, List<SettingDescriptor> settings, int instructionLimit) {
        this(apiRegistry, settings, null, instructionLimit);
    }

    public VirtualMachine(ApiRegistry apiRegistry, List<SettingDescriptor> settings,
                          io.velora.internal.setting.SettingStore settingStore, int instructionLimit) {
        this(apiRegistry, settings, settingStore, instructionLimit, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public VirtualMachine(ApiRegistry apiRegistry, List<SettingDescriptor> settings,
                          io.velora.internal.setting.SettingStore settingStore, int instructionLimit,
                          int maxCallDepth, int maxStringLength) {
        this.apiRegistry = apiRegistry;
        this.settings = settings;
        this.settingStore = settingStore;
        this.instructionLimit = instructionLimit;
        this.maxCallDepth = maxCallDepth;
        this.maxStringLength = maxStringLength;
    }

    private void initializeFields(CompiledModule module) {
        if (module == currentModule) return;
        currentModule = module;
        staticFields.clear();
        instanceFields.clear();
        for (CompiledModule.FieldInitializer fi : module.fieldInitializers()) {
            if (fi.isStatic()) {
                staticFields.put(fi.fieldIndex(), fi.initialValue());
            } else {
                instanceFields.put(fi.fieldIndex(), fi.initialValue());
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
                                "Instruction limit exceeded", 0, fiberId), instructions, System.nanoTime() - startTime);
                    }
                    return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.INSTRUCTION_LIMIT,
                            0, instructions, System.nanoTime() - startTime);
                }

                int ordinal = code[frame.ip];
                Opcode[] opcodes = Opcode.values();
                if (ordinal < 0 || ordinal >= opcodes.length) {
                    return VmExecutionResult.failure(VmError.of(DiagnosticCode.BYTECODE_INVALID_OPCODE,
                            "Invalid opcode: " + ordinal, 0, fiberId), instructions, System.nanoTime() - startTime);
                }
                Opcode op = opcodes[ordinal];
                int[] operands = new int[op.operandWords()];
                for (int i = 0; i < op.operandWords(); i++) {
                    operands[i] = code[frame.ip + 1 + i];
                }
                frame.ip += op.instructionWords();
                instructions++;

                switch (op) {
                    case CONST -> {
                        ConstantPool.Tag tag = module.constantPool().tag(operands[0]);
                        ScriptValue cv = constToValue(module.constantPool(), operands[0], tag);
                        if (cv instanceof StringValue sv && sv.value().length() > maxStringLength) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_RESOURCE_LIMIT,
                                    "String length limit exceeded", 0, fiberId), instructions, System.nanoTime() - startTime);
                        }
                        stack.push(cv);
                    }
                    case NULL -> stack.push(PrimitiveValue.nullValue());
                    case TRUE -> stack.push(PrimitiveValue.of(true));
                    case FALSE -> stack.push(PrimitiveValue.of(false));
                    case LOAD_LOCAL -> stack.push(frame.locals[operands[0]]);
                    case STORE_LOCAL -> frame.locals[operands[0]] = stack.pop();
                    case LOAD_FIELD -> {
                        if (host != null) {
                            stack.push(host.loadField(operands[0]));
                        } else {
                            stack.push(instanceFields.getOrDefault(operands[0], PrimitiveValue.nullValue()));
                        }
                    }
                    case STORE_FIELD -> {
                        ScriptValue val = stack.pop();
                        if (host != null) host.storeField(operands[0], val);
                        else instanceFields.put(operands[0], val);
                    }
                    case LOAD_SETTING -> {
                        if (settingStore != null) {
                            var sv = settingStore.getByIndex(operands[0]);
                            stack.push(sv != null ? javaToValue(sv.value()) : PrimitiveValue.nullValue());
                        } else if (operands[0] < settings.size()) {
                            Object def = settings.get(operands[0]).defaultValue();
                            stack.push(javaToValue(def));
                        } else {
                            stack.push(PrimitiveValue.nullValue());
                        }
                    }
                    case LOAD_STATIC -> {
                        if (host != null) {
                            stack.push(host.loadStatic(operands[0]));
                        } else {
                            stack.push(staticFields.getOrDefault(operands[0], PrimitiveValue.nullValue()));
                        }
                    }
                    case STORE_STATIC -> {
                        ScriptValue sval = stack.pop();
                        if (host != null) host.storeStatic(operands[0], sval);
                        else staticFields.put(operands[0], sval);
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
                    case JUMP -> frame.ip = operands[0];
                    case JUMP_IF_FALSE -> { if (!toBoolean(stack.pop())) frame.ip = operands[0]; }
                    case JUMP_IF_TRUE -> { if (toBoolean(stack.pop())) frame.ip = operands[0]; }
                    case LOOP -> frame.ip = operands[0];
                    case RETURN -> {
                        ScriptValue retVal = stack.isEmpty() ? PrimitiveValue.nullValue() : stack.pop();
                        callStack.pop();
                        if (callStack.isEmpty()) {
                            return VmExecutionResult.success(retVal, instructions, System.nanoTime() - startTime);
                        }
                        stack.push(retVal);
                    }
                    case CALL -> {
                        int funcIdx = operands[0];
                        int argCount = operands[1];
                        if (callStack.size() > maxCallDepth) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_RESOURCE_LIMIT,
                                    "Call depth limit exceeded", 0, fiberId), instructions, System.nanoTime() - startTime);
                        }
                        CompiledFunction target = module.function(funcIdx);
                        if (target == null) {
                            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                    "Function not found: " + funcIdx, 0, fiberId), instructions, System.nanoTime() - startTime);
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
                        int apiIdx = operands[0];
                        int argCount = operands[1];
                        FunctionDescriptor fd = apiRegistry.findByIndex(apiIdx);
                        if (fd == null || fd.invoker() == null) {
                            for (int i = 0; i < argCount; i++) stack.pop();
                            stack.push(PrimitiveValue.nullValue());
                        } else {
                            if (fd.permission() != null && module.maximumPermissions() != null && !module.maximumPermissions().isEmpty() && host == null) {
                                for (int i = 0; i < argCount; i++) stack.pop();
                                return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                        "Permission denied: " + fd.permission().id(), 0, fiberId), instructions, System.nanoTime() - startTime);
                            }
                            Object[] apiArgs = new Object[argCount];
                            for (int i = argCount - 1; i >= 0; i--) {
                                apiArgs[i] = stack.pop().boxed();
                            }
                            try {
                                Object result = fd.invoker().invoke(new SimpleFunctionContext(fd, apiArgs, module.scriptId(), fiberId));
                                ScriptValue sv = javaToValue(result);
                                if (sv instanceof HandleValue && !fd.returnType().isHandle()) {
                                    return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                            "Unsupported return type: host handle not allowed for " + fd.returnType().name(), 0, fiberId), instructions, System.nanoTime() - startTime);
                                }
                                stack.push(sv);
                            } catch (Throwable t) {
                                return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                        t.getMessage(), 0, fiberId), instructions, System.nanoTime() - startTime);
                            }
                        }
                    }
                    case CALL_SUSPEND -> {
                        int apiIdx = operands[0];
                        int argCount = operands[1];
                        FunctionDescriptor fd = apiRegistry.findByIndex(apiIdx);
                        if (fd == null || fd.invoker() == null) {
                            for (int i = 0; i < argCount; i++) stack.pop();
                            stack.push(PrimitiveValue.nullValue());
                        } else {
                            Object[] apiArgs = new Object[argCount];
                            for (int i = argCount - 1; i >= 0; i--) {
                                apiArgs[i] = stack.pop().boxed();
                            }
                            try {
                                Object result = fd.invoker().invoke(new SimpleFunctionContext(fd, apiArgs, module.scriptId(), fiberId));
                                if (result instanceof VeloraTask<?> task) {
                                    if (task.state() == TaskState.PENDING) {
                                        long taskId = System.identityHashCode(task);
                                        if (host != null) {
                                            host.watchTask(fiberId, taskId, task);
                                        }
                                        return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.AWAIT,
                                                taskId, instructions, System.nanoTime() - startTime);
                                    } else if (task.state() == TaskState.SUCCEEDED) {
                                        stack.push(javaToValue(task.result()));
                                    } else {
                                        return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                                "Task " + task.state(), 0, fiberId), instructions, System.nanoTime() - startTime);
                                    }
                                } else {
                                    ScriptValue sv = javaToValue(result);
                                    if (sv instanceof HandleValue && !fd.returnType().isHandle()) {
                                        return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                                "Unsupported return type: host handle not allowed for " + fd.returnType().name(), 0, fiberId), instructions, System.nanoTime() - startTime);
                                    }
                                    stack.push(sv);
                                }
                            } catch (Throwable t) {
                                return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                                        t.getMessage(), 0, fiberId), instructions, System.nanoTime() - startTime);
                            }
                        }
                    }
                    case GET_MEMBER -> {
                        String memberName = module.constantPool().stringValue(operands[0]);
                        ScriptValue recv = stack.pop();
                        if (recv instanceof StringValue sv && memberName.equals("length")) {
                            stack.push(PrimitiveValue.of(sv.value().length()));
                        } else if (recv instanceof ListValue lv && memberName.equals("size")) {
                            stack.push(PrimitiveValue.of(lv.elements().size()));
                        } else if (recv instanceof MapValue mv && memberName.equals("size")) {
                            stack.push(PrimitiveValue.of(mv.entries().size()));
                        } else {
                            stack.push(PrimitiveValue.nullValue());
                        }
                    }
                    case SET_MEMBER -> {
                        String memberName = module.constantPool().stringValue(operands[0]);
                        ScriptValue val = stack.pop();
                        ScriptValue recv = stack.pop();
                    }
                    case CREATE_LIST -> {
                        int count = operands[0];
                        List<ScriptValue> elements = new ArrayList<>();
                        for (int i = 0; i < count; i++) elements.add(stack.pop());
                        Collections.reverse(elements);
                        stack.push(new ListValue(elements));
                    }
                    case CREATE_MAP -> {
                        int count = operands[0];
                        Map<ScriptValue, ScriptValue> entries = new LinkedHashMap<>();
                        for (int i = 0; i < count; i++) {
                            ScriptValue v = stack.pop();
                            ScriptValue k = stack.pop();
                            entries.put(k, v);
                        }
                        stack.push(new MapValue(entries));
                    }
                    case GET_INDEX -> {
                        ScriptValue idx = stack.pop();
                        ScriptValue recv = stack.pop();
                        if (recv instanceof ListValue l && idx instanceof PrimitiveValue.IntV iv) {
                            int i = iv.value();
                            stack.push(i >= 0 && i < l.elements().size() ? l.elements().get(i) : PrimitiveValue.nullValue());
                        } else if (recv instanceof MapValue m) {
                            stack.push(m.entries().getOrDefault(idx, PrimitiveValue.nullValue()));
                        } else {
                            stack.push(PrimitiveValue.nullValue());
                        }
                    }
                    case SPAWN -> {
                        int funcIdx = operands[0];
                        int ac = operands[1];
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
                            stack.push(PrimitiveValue.nullValue());
                        }
                    }
                    case AWAIT -> {
                        ScriptValue task = stack.pop();
                        if (host != null && task instanceof TaskValue tv) {
                            host.awaitFiber(fiberId, tv.taskId());
                            return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.AWAIT,
                                    tv.taskId(), instructions, System.nanoTime() - startTime);
                        }
                        stack.push(PrimitiveValue.nullValue());
                    }
                    case DELAY -> {
                        ScriptValue d = stack.pop();
                        long nanos = 0;
                        if (d instanceof PrimitiveValue.LongV lv) nanos = lv.value();
                        else if (d instanceof PrimitiveValue.IntV iv) nanos = iv.value();
                        if (host != null) {
                            host.sleepFiber(fiberId, System.nanoTime() + nanos);
                            return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.SLEEP,
                                    nanos, instructions, System.nanoTime() - startTime);
                        }
                    }
                    case YIELD -> {
                        if (host != null) {
                            host.yieldFiber(fiberId);
                            return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.YIELD,
                                    0, instructions, System.nanoTime() - startTime);
                        }
                    }
                    case CHECK_CANCELLED -> {
                        if (host != null && host.isCancelled(fiberId)) {
                            return VmExecutionResult.suspended(VmExecutionResult.SuspendReason.CANCELLED,
                                    0, instructions, System.nanoTime() - startTime);
                        }
                    }
                    case LINE -> {}
                    case BREAKPOINT -> {}
                }
            }

            return VmExecutionResult.success(PrimitiveValue.nullValue(), instructions, System.nanoTime() - startTime);

        } catch (Throwable t) {
            return VmExecutionResult.failure(VmError.of(DiagnosticCode.RUNTIME_API_ERROR,
                    t.getMessage(), 0, fiberId), instructions, System.nanoTime() - startTime);
        }
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

    public static ScriptValue javaToValue(Object o) {
        if (o == null) return PrimitiveValue.nullValue();
        if (o instanceof Integer i) return PrimitiveValue.of(i);
        if (o instanceof Long l) return PrimitiveValue.of(l);
        if (o instanceof Float f) return PrimitiveValue.of(f);
        if (o instanceof Double d) return PrimitiveValue.of(d);
        if (o instanceof Boolean b) return PrimitiveValue.of(b);
        if (o instanceof Byte b) return PrimitiveValue.of(b);
        if (o instanceof Character c) return PrimitiveValue.of(c);
        if (o instanceof String s) return new StringValue(s);
        if (o instanceof List<?> list) {
            List<ScriptValue> elements = new ArrayList<>();
            for (Object e : list) elements.add(javaToValue(e));
            return new ListValue(elements);
        }
        if (o instanceof Map<?, ?> map) {
            Map<ScriptValue, ScriptValue> entries = new LinkedHashMap<>();
            for (var e : map.entrySet()) {
                entries.put(javaToValue(e.getKey()), javaToValue(e.getValue()));
            }
            return new MapValue(entries);
        }
        return new HandleValue(o.getClass().getSimpleName(), o);
    }

    private boolean toBoolean(ScriptValue v) {
        if (v instanceof PrimitiveValue.BooleanV b) return b.value();
        if (v instanceof PrimitiveValue.NullV) return false;
        return v != null && !v.isNull();
    }

    private boolean equal(ScriptValue a, ScriptValue b) {
        if (a.isNull() && b.isNull()) return true;
        if (a.isNull() || b.isNull()) return false;
        return a.boxed().equals(b.boxed());
    }

    private int compare(ScriptValue a, ScriptValue b) {
        if (a.boxed() instanceof Number n && b.boxed() instanceof Number n2) {
            return Double.compare(n.doubleValue(), n2.doubleValue());
        }
        return 0;
    }

    private ScriptValue add(ScriptValue a, ScriptValue b) {
        if (a instanceof PrimitiveValue.IntV ia && b instanceof PrimitiveValue.IntV ib) return PrimitiveValue.of(ia.value() + ib.value());
        if (a instanceof PrimitiveValue.LongV la && b instanceof PrimitiveValue.LongV lb) return PrimitiveValue.of(la.value() + lb.value());
        if (a instanceof PrimitiveValue.DoubleV da && b instanceof PrimitiveValue.DoubleV db) return PrimitiveValue.of(da.value() + db.value());
        if (a instanceof PrimitiveValue.FloatV fa && b instanceof PrimitiveValue.FloatV fb) return PrimitiveValue.of(fa.value() + fb.value());
        if (a instanceof StringValue || b instanceof StringValue) return new StringValue(valueToString(a) + valueToString(b));
        return PrimitiveValue.nullValue();
    }

    private String valueToString(ScriptValue v) {
        if (v instanceof StringValue sv) return sv.value();
        if (v == null || v.isNull()) return "null";
        Object boxed = v.boxed();
        return boxed != null ? boxed.toString() : "null";
    }

    private ScriptValue sub(ScriptValue a, ScriptValue b) {
        if (a instanceof PrimitiveValue.IntV ia && b instanceof PrimitiveValue.IntV ib) return PrimitiveValue.of(ia.value() - ib.value());
        if (a instanceof PrimitiveValue.LongV la && b instanceof PrimitiveValue.LongV lb) return PrimitiveValue.of(la.value() - lb.value());
        if (a instanceof PrimitiveValue.DoubleV da && b instanceof PrimitiveValue.DoubleV db) return PrimitiveValue.of(da.value() - db.value());
        return PrimitiveValue.nullValue();
    }

    private ScriptValue mul(ScriptValue a, ScriptValue b) {
        if (a instanceof PrimitiveValue.IntV ia && b instanceof PrimitiveValue.IntV ib) return PrimitiveValue.of(ia.value() * ib.value());
        if (a instanceof PrimitiveValue.LongV la && b instanceof PrimitiveValue.LongV lb) return PrimitiveValue.of(la.value() * lb.value());
        if (a instanceof PrimitiveValue.DoubleV da && b instanceof PrimitiveValue.DoubleV db) return PrimitiveValue.of(da.value() * db.value());
        return PrimitiveValue.nullValue();
    }

    private ScriptValue div(ScriptValue a, ScriptValue b) {
        if (a instanceof PrimitiveValue.IntV ia && b instanceof PrimitiveValue.IntV ib) {
            if (ib.value() == 0) throw new ArithmeticException("Division by zero");
            return PrimitiveValue.of(ia.value() / ib.value());
        }
        if (a instanceof PrimitiveValue.LongV la && b instanceof PrimitiveValue.LongV lb) {
            if (lb.value() == 0) throw new ArithmeticException("Division by zero");
            return PrimitiveValue.of(la.value() / lb.value());
        }
        if (a instanceof PrimitiveValue.DoubleV da && b instanceof PrimitiveValue.DoubleV db) {
            if (db.value() == 0) throw new ArithmeticException("Division by zero");
            return PrimitiveValue.of(da.value() / db.value());
        }
        return PrimitiveValue.nullValue();
    }

    private ScriptValue mod(ScriptValue a, ScriptValue b) {
        if (a instanceof PrimitiveValue.IntV ia && b instanceof PrimitiveValue.IntV ib) {
            if (ib.value() == 0) throw new ArithmeticException("Modulo by zero");
            return PrimitiveValue.of(ia.value() % ib.value());
        }
        return PrimitiveValue.nullValue();
    }

    private ScriptValue negate(ScriptValue a) {
        if (a instanceof PrimitiveValue.IntV ia) return PrimitiveValue.of(-ia.value());
        if (a instanceof PrimitiveValue.LongV la) return PrimitiveValue.of(-la.value());
        if (a instanceof PrimitiveValue.DoubleV da) return PrimitiveValue.of(-da.value());
        return PrimitiveValue.nullValue();
    }

    private record SimpleFunctionContext(FunctionDescriptor descriptor, Object[] args, String scriptId, long fiberId) implements FunctionContext {
        @Override public <T> T argument(String name, Class<T> type) { return type.cast(argument(name)); }
        @Override public <T> T argument(int index, Class<T> type) {
            Object value = args[index];
            if (type == int.class) return (T) (Object) Integer.valueOf(((Number) value).intValue());
            if (type == long.class) return (T) (Object) Long.valueOf(((Number) value).longValue());
            if (type == double.class) return (T) (Object) Double.valueOf(((Number) value).doubleValue());
            if (type == float.class) return (T) (Object) Float.valueOf(((Number) value).floatValue());
            if (type == boolean.class) return (T) (Object) Boolean.valueOf((Boolean) value);
            if (type == short.class) return (T) (Object) Short.valueOf(((Number) value).shortValue());
            if (type == byte.class) return (T) (Object) Byte.valueOf(((Number) value).byteValue());
            if (type == char.class) return (T) (Object) Character.valueOf((Character) value);
            return type.cast(value);
        }
        @Override public Object argument(String name) {
            if (descriptor != null) {
                for (int i = 0; i < descriptor.parameters().size(); i++) {
                    if (descriptor.parameters().get(i).name().equals(name)) {
                        return i < args.length ? args[i] : null;
                    }
                }
            }
            return null;
        }
        @Override public Object argument(int index) { return args[index]; }
        @Override public int argumentCount() { return args.length; }
        @Override public String scriptId() { return scriptId; }
        @Override public long fiberId() { return fiberId; }
    }
}
