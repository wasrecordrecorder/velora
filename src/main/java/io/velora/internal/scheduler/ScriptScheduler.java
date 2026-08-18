package io.velora.internal.scheduler;

import io.velora.api.VeloraLimits;
import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionContext;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.type.EnumType;
import io.velora.api.task.TaskFactory;
import io.velora.api.task.TaskState;
import io.velora.api.task.VeloraTaskSource;
import io.velora.api.task.VeloraTask;
import io.velora.api.type.VeloraType;
import io.velora.internal.bytecode.CompiledFunction;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.debug.RuntimeErrorStore;
import io.velora.internal.security.ResourceCounter;
import io.velora.internal.security.ResourceLimitViolation;
import io.velora.host.WorkerExecutor;
import io.velora.internal.vm.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class ScriptScheduler implements VmHost {

    private final VeloraLimits limits;
    private final ApiRegistry apiRegistry;
    private final FiberQueue readyQueue = new FiberQueue();
    private final List<ScriptFiber> pendingQueue = new ArrayList<>();
    private final SleepQueue sleepQueue = new SleepQueue();
    private final CompletionQueue completionQueue = new CompletionQueue();
    private final CancellationTree cancellationTree = new CancellationTree();
    private final SchedulerBudget budget;
    private final SchedulerMetrics metrics = new SchedulerMetrics();
    private long nextFiberId = 1;
    private long nextTaskId = -2;

    private final Map<Long, ScriptFiber> fibersById = new HashMap<>();
    private final Map<Long, Long> awaitingFibers = new HashMap<>();
    private final Map<Long, BufferedCompletion> bufferedCompletions = new HashMap<>();

    private final Map<String, Map<Integer, ScriptValue>> scriptInstanceFields = new HashMap<>();
    private final Map<String, Map<Integer, ScriptValue>> scriptStaticFields = new HashMap<>();
    private final Set<String> initializedScripts = new HashSet<>();
    private final Map<String, Set<Long>> fibersByScript = new HashMap<>();
    private final Map<String, Map<Long, VeloraTask<?>>> tasksByScript = new HashMap<>();
    private final Map<String, ResourceCounter> resourcesByScript = new HashMap<>();
    private final Map<String, Long> persistentMemoryByScript = new HashMap<>();
    private final Map<String, Integer> apiCostByScript = new HashMap<>();
    private final Map<String, Long> totalApiCostByScript = new HashMap<>();
    private final Map<String, Long> apiCallsByScript = new HashMap<>();
    private final Map<String, Long> failuresByScript = new HashMap<>();
    private final Map<String, Long> cancellationsByScript = new HashMap<>();
    private final Map<String, Long> tickTimeByScript = new HashMap<>();
    private final Map<String, Long> instructionsByScript = new HashMap<>();
    private final Map<String, io.velora.internal.setting.SettingStore> settingStores = new HashMap<>();
    private final RuntimeErrorStore errorStore;
    private final WorkerExecutor workerExecutor;
    private final ConstantRegistry constantRegistry;
    private final TypeRegistry typeRegistry;
    private final LongSupplier nanoTime;
    private final BooleanSupplier mainThread;
    private long currentFiberId;
    private long currentTickNanos;

    public ScriptScheduler(VeloraLimits limits, ApiRegistry apiRegistry) {
        this(limits, apiRegistry, new RuntimeErrorStore(100), null, null, null);
    }

    public ScriptScheduler(VeloraLimits limits, ApiRegistry apiRegistry, RuntimeErrorStore errorStore) {
        this(limits, apiRegistry, errorStore, null, null, null);
    }

    public ScriptScheduler(VeloraLimits limits, ApiRegistry apiRegistry, RuntimeErrorStore errorStore, WorkerExecutor workerExecutor) {
        this(limits, apiRegistry, errorStore, workerExecutor, null, null);
    }

    public ScriptScheduler(VeloraLimits limits, ApiRegistry apiRegistry, RuntimeErrorStore errorStore, WorkerExecutor workerExecutor, ConstantRegistry constantRegistry, TypeRegistry typeRegistry) {
        this(limits, apiRegistry, errorStore, workerExecutor, constantRegistry, typeRegistry, System::nanoTime);
    }

    public ScriptScheduler(VeloraLimits limits, ApiRegistry apiRegistry, RuntimeErrorStore errorStore, WorkerExecutor workerExecutor, ConstantRegistry constantRegistry, TypeRegistry typeRegistry, LongSupplier nanoTime) {
        this(limits, apiRegistry, errorStore, workerExecutor, constantRegistry, typeRegistry, nanoTime, () -> true);
    }

    public ScriptScheduler(VeloraLimits limits, ApiRegistry apiRegistry, RuntimeErrorStore errorStore, WorkerExecutor workerExecutor, ConstantRegistry constantRegistry, TypeRegistry typeRegistry, LongSupplier nanoTime, BooleanSupplier mainThread) {
        this.limits = limits;
        this.apiRegistry = apiRegistry;
        this.errorStore = errorStore;
        this.workerExecutor = workerExecutor;
        this.constantRegistry = constantRegistry;
        this.typeRegistry = typeRegistry;
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.budget = new SchedulerBudget(limits);
    }

    public ScriptFiber spawnFiber(String scriptId, int functionIndex, ScriptValue[] args) {
        return spawnInternal(scriptId, functionIndex, args, -1, false, false);
    }

    public ScriptFiber spawnEventFiber(String scriptId, int functionIndex, ScriptValue[] args) {
        return spawnEventFiber(scriptId, functionIndex, args, 0);
    }

    public ScriptFiber spawnEventFiber(String scriptId, int functionIndex, ScriptValue[] args, int eventCost) {
        ScriptFiber fiber = spawnInternal(scriptId, functionIndex, args, -1, false, true);
        if (fiber != null) fiber.pendingApiCost(eventCost);
        return fiber;
    }

    private ScriptFiber spawnInternal(String scriptId, int functionIndex, ScriptValue[] args, long parentId, boolean pending, boolean eventFiber) {
        ResourceCounter counter = resourcesByScript.computeIfAbsent(scriptId, key -> new ResourceCounter());
        if (limits.maxFibersPerScript() > 0 && counter.fibers() >= limits.maxFibersPerScript()) return null;
        long memory;
        try { memory = estimateValues(args) + 256L; }
        catch (ResourceLimitViolation ignored) { return null; }
        if (exceedsMemory(counter.memoryUsed(), memory)) return null;
        ScriptFiber fiber = new ScriptFiber(nextFiberId++, scriptId, functionIndex, args, nanoTime.getAsLong());
        fiber.parentId(parentId);
        fiber.eventFiber(eventFiber);
        fiber.reservedMemory(memory);
        fibersById.put(fiber.id(), fiber);
        fibersByScript.computeIfAbsent(scriptId, key -> new HashSet<>()).add(fiber.id());
        counter.reserveFiber();
        counter.reserveMemory(memory);
        if (parentId >= 0) cancellationTree.addChild(parentId, fiber.id());
        if (pending) pendingQueue.add(fiber); else readyQueue.add(fiber);
        return fiber;
    }

    public ScriptFiber spawnFiberAndAwait(String scriptId, int functionIndex, ScriptValue[] args,
                                            Map<String, CompiledModule> modules,
                                            Map<String, List<io.velora.api.setting.SettingDescriptor>> settings) {
        ScriptFiber fiber = spawnFiber(scriptId, functionIndex, args);
        if (fiber == null) return null;
        while (!fiber.isDone()) tick(nanoTime.getAsLong(), modules, settings);
        return fiber;
    }

    public ScriptFiber spawnFiberAndAwait(String scriptId, int functionIndex, ScriptValue[] args) {
        return spawnFiberAndAwait(scriptId, functionIndex, args, Map.of(), Map.of());
    }

    public void cancelFiber(long fiberId) {
        cancellationTree.cancel(fiberId);
    }

    @Override
    public boolean isCancelled(long fiberId) {
        return cancellationTree.isCancelled(fiberId);
    }

    @Override
    public long spawnFiber(String scriptId, int functionIndex, ScriptValue[] args, long parentId) {
        ScriptFiber fiber = spawnInternal(scriptId, functionIndex, args, parentId, true, false);
        return fiber != null ? fiber.id() : -1;
    }

    @Override
    public long nanoTime() {
        return nanoTime.getAsLong();
    }

    @Override
    public boolean isMainThread() {
        return mainThread.getAsBoolean();
    }

    @Override
    public void sleepFiber(long fiberId, long wakeupNanos) {
        long duration = Math.max(0, wakeupNanos - nanoTime.getAsLong());
        ScriptFiber fiber = fibersById.get(fiberId);
        if (fiber != null) {
            fiber.sleepUntilNanos(currentTickNanos + duration);
            fiber.state(FiberState.SLEEPING);
        }
    }

    @Override
    public void awaitFiber(long fiberId, long taskId) {
        ScriptFiber fiber = fibersById.get(fiberId);
        if (fiber == null) return;
        fiber.awaitTaskId(taskId);
        fiber.state(FiberState.WAITING_TASK);
        awaitingFibers.put(taskId, fiberId);
        BufferedCompletion buffered = bufferedCompletions.remove(taskId);
        if (buffered != null && buffered.parentFiberId() == fiberId) {
            CompletionQueue.CompletionRecord record = buffered.record();
            completionQueue.offer(record.taskId(), record.success(), record.result(), record.error());
        }
    }

    @Override
    public long watchTask(long fiberId, VeloraTask<?> task, VeloraType resultType) {
        ScriptFiber fiber = fibersById.get(fiberId);
        if (fiber == null) return 0;
        String scriptId = fiber.scriptId();
        ResourceCounter counter = resourcesByScript.computeIfAbsent(scriptId, key -> new ResourceCounter());
        if (limits.maxTasksPerScript() > 0 && counter.tasks() >= limits.maxTasksPerScript()) return 0;
        long taskId = nextTaskId--;
        task.onComplete(completed -> {
            try {
                if (completed.state() == TaskState.SUCCEEDED) {
                    completionQueue.offer(taskId, true, VirtualMachine.javaToValue(resultType, completed.result()), null);
                } else {
                    Throwable error = completed.state() == TaskState.FAILED
                            ? completed.failure()
                            : new java.util.concurrent.CancellationException("Task cancelled");
                    completionQueue.offer(taskId, false, PrimitiveValue.nullValue(), error);
                }
            } catch (Throwable error) {
                completionQueue.offer(taskId, false, PrimitiveValue.nullValue(), error);
            }
        });
        counter.reserveTask();
        tasksByScript.computeIfAbsent(scriptId, key -> new HashMap<>()).put(taskId, task);
        awaitFiber(fiberId, taskId);
        return taskId;
    }

    @Override
    public long watchWorkerCall(long fiberId, FunctionDescriptor descriptor, FunctionContext context) {
        if (workerExecutor == null) return 0;
        VeloraTaskSource<Object> source = TaskFactory.create();
        long taskId = watchTask(fiberId, source.task(), descriptor.returnType());
        if (taskId == 0) return 0;
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<VeloraTask<?>> nested = new AtomicReference<>();
        source.onCancel(() -> {
            cancelled.set(true);
            VeloraTask<?> task = nested.get();
            if (task != null) task.cancel();
        });
        try {
            workerExecutor.execute(() -> {
                if (cancelled.get()) return;
                try {
                    Object result = descriptor.invoker().invoke(context);
                    if (result instanceof VeloraTask<?> task) {
                        nested.set(task);
                        if (cancelled.get()) {
                            task.cancel();
                            return;
                        }
                        task.onComplete(completed -> {
                            switch (completed.state()) {
                                case SUCCEEDED -> source.succeed(completed.result());
                                case FAILED -> source.fail(completed.failure());
                                case CANCELLED -> source.cancel();
                                default -> {}
                            }
                        });
                    } else {
                        source.succeed(result);
                    }
                } catch (Throwable error) {
                    source.fail(error);
                }
            });
        } catch (Throwable error) {
            source.fail(error);
        }
        return taskId;
    }

    @Override
    public ScriptValue loadQualified(String namespace, String member) {
        if (constantRegistry != null) {
            ConstantRegistry.Constant constant = constantRegistry.find(namespace, member);
            if (constant != null) return VirtualMachine.javaToValue(constant.type(), constant.value());
        }
        if (typeRegistry != null && typeRegistry.find(namespace) instanceof EnumType enumType) {
            EnumType.Constant constant = enumType.constant(member);
            if (constant != null) return VirtualMachine.javaToValue(enumType, constant.value());
        }
        return null;
    }

    @Override
    public boolean consumeApiCost(long fiberId, int cost) {
        ScriptFiber fiber = fibersById.get(fiberId);
        if (fiber == null || !consumeApiCost(fiber.scriptId(), cost)) return false;
        apiCallsByScript.merge(fiber.scriptId(), 1L, Long::sum);
        return true;
    }

    private boolean consumeApiCost(String scriptId, int cost) {
        int used = apiCostByScript.getOrDefault(scriptId, 0);
        long next = (long) used + cost;
        if (next > limits.apiCostPerScriptTick()) return false;
        apiCostByScript.put(scriptId, (int) next);
        totalApiCostByScript.merge(scriptId, (long) cost, Long::sum);
        return true;
    }

    public void cancelScriptTasks(String scriptId) {
        Map<Long, VeloraTask<?>> tasks = tasksByScript.remove(scriptId);
        if (tasks != null) {
            for (VeloraTask<?> task : tasks.values()) {
                task.cancel();
            }
        }
    }

    public void stopScript(String scriptId) {
        cancelScriptTasks(scriptId);
        Set<Long> ids = new HashSet<>(fibersByScript.getOrDefault(scriptId, Set.of()));
        for (Long id : ids) {
            ScriptFiber fiber = fibersById.get(id);
            if (fiber != null) {
                fiber.state(FiberState.CANCELLED);
                retireFiber(fiber);
            }
        }
        bufferedCompletions.entrySet().removeIf(entry -> entry.getValue().scriptId().equals(scriptId));
    }

    public void cleanupScript(String scriptId) {
        stopScript(scriptId);
        scriptInstanceFields.remove(scriptId);
        scriptStaticFields.remove(scriptId);
        initializedScripts.remove(scriptId);
        settingStores.remove(scriptId);
        resourcesByScript.remove(scriptId);
        persistentMemoryByScript.remove(scriptId);
        apiCostByScript.remove(scriptId);
        tickTimeByScript.remove(scriptId);
    }

    public List<VeloraTask<?>> tasksForScript(String scriptId) {
        Map<Long, VeloraTask<?>> tasks = tasksByScript.get(scriptId);
        return tasks != null ? List.copyOf(tasks.values()) : List.of();
    }

    public void setSettingStore(String scriptId, io.velora.internal.setting.SettingStore store) {
        if (store != null) settingStores.put(scriptId, store);
    }

    @Override
    public void yieldFiber(long fiberId) {
        ScriptFiber fiber = fibersById.get(fiberId);
        if (fiber != null) {
            fiber.state(FiberState.PAUSED);
        }
    }

    @Override
    public ScriptValue loadField(int fieldIndex) {
        ScriptFiber fiber = fibersById.get(currentFiberId);
        if (fiber != null) {
            Map<Integer, ScriptValue> fields = scriptInstanceFields.get(fiber.scriptId());
            if (fields != null) return fields.getOrDefault(fieldIndex, PrimitiveValue.nullValue());
        }
        return PrimitiveValue.nullValue();
    }

    @Override
    public void storeField(int fieldIndex, ScriptValue value) {
        ScriptFiber fiber = fibersById.get(currentFiberId);
        if (fiber != null) {
            storeValue(scriptInstanceFields, fiber.scriptId(), fieldIndex, value);
        }
    }

    @Override
    public ScriptValue loadStatic(int fieldIndex) {
        ScriptFiber fiber = fibersById.get(currentFiberId);
        if (fiber != null) {
            Map<Integer, ScriptValue> fields = scriptStaticFields.get(fiber.scriptId());
            if (fields != null) return fields.getOrDefault(fieldIndex, PrimitiveValue.nullValue());
        }
        return PrimitiveValue.nullValue();
    }

    @Override
    public void storeStatic(int fieldIndex, ScriptValue value) {
        ScriptFiber fiber = fibersById.get(currentFiberId);
        if (fiber != null) {
            storeValue(scriptStaticFields, fiber.scriptId(), fieldIndex, value);
        }
    }

    public ScriptValue loadFieldForScript(String scriptId, int fieldIndex) {
        Map<Integer, ScriptValue> fields = scriptInstanceFields.get(scriptId);
        if (fields != null) return fields.getOrDefault(fieldIndex, PrimitiveValue.nullValue());
        return PrimitiveValue.nullValue();
    }

    public void storeFieldForScript(String scriptId, int fieldIndex, ScriptValue value) {
        storeValue(scriptInstanceFields, scriptId, fieldIndex, value);
    }

    public ScriptValue loadStaticForScript(String scriptId, int fieldIndex) {
        Map<Integer, ScriptValue> fields = scriptStaticFields.get(scriptId);
        if (fields != null) return fields.getOrDefault(fieldIndex, PrimitiveValue.nullValue());
        return PrimitiveValue.nullValue();
    }

    public void storeStaticForScript(String scriptId, int fieldIndex, ScriptValue value) {
        storeValue(scriptStaticFields, scriptId, fieldIndex, value);
    }

    public void tick(long nowNanos, Map<String, CompiledModule> modules, Map<String, List<SettingDescriptor>> scriptSettings) {
        long wallStartNanos = System.nanoTime();
        this.currentTickNanos = nowNanos;
        budget.resetTick();
        apiCostByScript.clear();
        tickTimeByScript.clear();
        List<ScriptFiber> terminalFibers = new ArrayList<>();
        for (ScriptFiber fiber : fibersById.values()) fiber.instructionsThisTick(0);

        for (ScriptFiber f : pendingQueue) {
            readyQueue.add(f);
        }
        pendingQueue.clear();

        List<ScriptFiber> woken = sleepQueue.wake(nowNanos);
        for (ScriptFiber f : woken) {
            f.state(FiberState.READY);
            readyQueue.add(f);
        }

        for (ScriptFiber f : sleepQueue.removeCancelled(cancellationTree::isCancelled)) {
            f.state(FiberState.CANCELLED);
            completionQueue.offer(f.id(), false, PrimitiveValue.nullValue(), new RuntimeException("Cancelled"));
            terminalFibers.add(f);
        }

        for (ScriptFiber fiber : new ArrayList<>(fibersById.values())) {
            if (cancellationTree.isCancelled(fiber.id()) && fiber.state() == FiberState.WAITING_TASK) {
                fiber.state(FiberState.CANCELLED);
                completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(), new RuntimeException("Cancelled"));
                terminalFibers.add(fiber);
            }
        }
        drainCompletions();

        int fibersExecuted = 0;
        int totalInstructions = 0;

        while (!readyQueue.isEmpty() && !budget.wallTimeExceeded(System.nanoTime())) {
            if (budget.engineInstructionsExceeded()) break;
            ScriptFiber fiber = readyQueue.poll();
            if (fibersById.get(fiber.id()) != fiber) continue;
            if (budget.scriptInstructionsExceeded(fiber.scriptId())) {
                pendingQueue.add(fiber);
                continue;
            }
            if (cancellationTree.isCancelled(fiber.id())) {
                fiber.state(FiberState.CANCELLED);
                completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(), new RuntimeException("Cancelled"));
                terminalFibers.add(fiber);
                drainCompletions();
                continue;
            }
            if (fiber.pendingApiCost() > 0) {
                if (!consumeApiCost(fiber.scriptId(), fiber.pendingApiCost())) {
                    failFiber(fiber, new RuntimeException("API cost limit exceeded"), io.velora.api.compiler.DiagnosticCode.RUNTIME_RESOURCE_LIMIT, savedLine(fiber));
                    terminalFibers.add(fiber);
                    drainCompletions();
                    continue;
                }
                fiber.pendingApiCost(0);
            }

            CompiledModule module = modules.get(fiber.scriptId());
            if (module == null) {
                failFiber(fiber, new IllegalStateException("Compiled module not found"), io.velora.api.compiler.DiagnosticCode.RUNTIME_API_ERROR, savedLine(fiber));
                terminalFibers.add(fiber);
                drainCompletions();
                continue;
            }

            // Initialize fields from module field initializers on first use
            if (!initializedScripts.contains(fiber.scriptId())) {
                try {
                    Map<Integer, ScriptValue> instFields = scriptInstanceFields.computeIfAbsent(fiber.scriptId(), k -> new HashMap<>());
                    Map<Integer, ScriptValue> statFields = scriptStaticFields.computeIfAbsent(fiber.scriptId(), k -> new HashMap<>());
                    for (CompiledModule.FieldInitializer fi : module.fieldInitializers()) {
                        if (fi.isStatic()) {
                            if (!statFields.containsKey(fi.fieldIndex())) storeValue(scriptStaticFields, fiber.scriptId(), fi.fieldIndex(), VirtualMachine.copyValue(fi.initialValue()));
                        } else if (!instFields.containsKey(fi.fieldIndex())) {
                            storeValue(scriptInstanceFields, fiber.scriptId(), fi.fieldIndex(), VirtualMachine.copyValue(fi.initialValue()));
                        }
                    }
                    initializedScripts.add(fiber.scriptId());
                } catch (ResourceLimitViolation violation) {
                    failFiber(fiber, violation, io.velora.api.compiler.DiagnosticCode.RUNTIME_RESOURCE_LIMIT, savedLine(fiber));
                    terminalFibers.add(fiber);
                    drainCompletions();
                    continue;
                }
            }

            currentFiberId = fiber.id();

            List<SettingDescriptor> settings = scriptSettings.getOrDefault(fiber.scriptId(), List.of());
            io.velora.internal.setting.SettingStore store = settingStores.get(fiber.scriptId());
            VirtualMachine vm = new VirtualMachine(apiRegistry, settings, store, budget.remainingFiberInstructions(fiber),
                    limits.maxCallDepth(), limits.maxStringLength(), limits.maxCollectionElements(), limits.maxCollectionDepth());
            fiber.state(FiberState.RUNNING);

            ValueStack stack;
            Deque<CallFrame> callStack;
            int startInstructions;

            if (fiber.savedStack() != null) {
                stack = fiber.savedStack();
                callStack = fiber.savedCallStack();
                startInstructions = 0;
                fiber.savedStack(null);
                fiber.savedCallStack(null);
            } else {
                CompiledFunction fn = module.function(fiber.functionIndex());
                if (fn == null) {
                    failFiber(fiber, new IllegalStateException("Function not found: " + fiber.functionIndex()), io.velora.api.compiler.DiagnosticCode.RUNTIME_API_ERROR, 0);
                    terminalFibers.add(fiber);
                    drainCompletions();
                    continue;
                }
                fiber.functionName(fn.name());
                stack = new ValueStack(Math.max(fn.maxStack(), 16));
                callStack = new ArrayDeque<>();
                CallFrame rootFrame = new CallFrame(fn, 0, 0);
                for (int i = 0; i < Math.min(fiber.args().length, fn.parameterCount()); i++) {
                    rootFrame.locals[i] = fiber.args()[i];
                }
                callStack.push(rootFrame);
                startInstructions = 0;
            }

            VmExecutionResult result = vm.execute(module, fiber.id(), this, stack, callStack, startInstructions);
            tickTimeByScript.merge(fiber.scriptId(), result.wallTimeNanos(), Long::sum);

            if (result.isSuspended()) {
                fiber.savedStack(stack);
                fiber.savedCallStack(callStack);
                fiber.savedInstructions(result.instructionsExecuted());

                switch (result.suspendReason()) {
                    case SLEEP -> {
                        fiber.resetInstructionLimits();
                        fiber.state(FiberState.SLEEPING);
                        sleepQueue.add(fiber, fiber.sleepUntilNanos());
                    }
                    case AWAIT -> {
                        fiber.resetInstructionLimits();
                        fiber.state(FiberState.WAITING_TASK);
                    }
                    case YIELD -> {
                        fiber.resetInstructionLimits();
                        fiber.state(FiberState.READY);
                        readyQueue.add(fiber);
                    }
                    case CANCELLED -> {
                        fiber.state(FiberState.CANCELLED);
                        completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(),
                                new RuntimeException("Cancelled"));
                    }
                    case INSTRUCTION_LIMIT -> {
                        fiber.incrementInstructionLimits();
                        if (fiber.consecutiveInstructionLimits() >= 5) {
                            failFiber(fiber, new RuntimeException("Runaway script: instruction limit exceeded 5 consecutive times"), io.velora.api.compiler.DiagnosticCode.RUNTIME_RESOURCE_LIMIT, currentLine(callStack));
                        } else {
                            fiber.state(FiberState.READY);
                            readyQueue.add(fiber);
                        }
                    }
                }
            } else if (result.success()) {
                fiber.result(result.returnValue());
                fiber.state(FiberState.COMPLETED);
                completionQueue.offer(fiber.id(), true, result.returnValue(), null);
            } else {
                failFiber(fiber, new RuntimeException(result.error().message()), result.error().code(), result.error().line());
            }

            drainCompletions();
            if (fiber.isDone()) terminalFibers.add(fiber);

            fibersExecuted++;
            totalInstructions += result.instructionsExecuted();
            budget.recordInstructions(fiber.scriptId(), result.instructionsExecuted());
            fiber.instructionsThisTick(fiber.instructionsThisTick() + result.instructionsExecuted());
            fiber.addInstructionsExecuted(result.instructionsExecuted());
            instructionsByScript.merge(fiber.scriptId(), (long) result.instructionsExecuted(), Long::sum);
        }

        drainCompletions();
        for (ScriptFiber fiber : new LinkedHashSet<>(terminalFibers)) retireFiber(fiber);
        long wallTime = System.nanoTime() - wallStartNanos;
        metrics.recordTick(fibersExecuted, totalInstructions, wallTime);
    }

    private void drainCompletions() {
        while (!completionQueue.isEmpty()) {
            CompletionQueue.CompletionRecord record = completionQueue.poll();
            Long awaitingFiberId = awaitingFibers.remove(record.taskId());
            if (awaitingFiberId == null) {
                ScriptFiber completed = fibersById.get(record.taskId());
                if (completed != null && completed.parentId() >= 0) {
                    bufferedCompletions.put(record.taskId(), new BufferedCompletion(completed.scriptId(), completed.parentId(), record));
                }
                continue;
            }
            ScriptFiber awaiting = fibersById.get(awaitingFiberId);
            if (awaiting == null) continue;
            if (record.taskId() < -1) releaseExternalTask(awaiting.scriptId(), record.taskId());
            if (awaiting.savedStack() == null || awaiting.isDone()) continue;
            awaiting.awaitTaskId(-1);
            if (record.success()) {
                try {
                    long memory = estimateValue(record.result(), 0);
                    ResourceCounter counter = resourcesByScript.computeIfAbsent(awaiting.scriptId(), key -> new ResourceCounter());
                    if (exceedsMemory(counter.memoryUsed(), memory)) throw new ResourceLimitViolation("Script memory limit exceeded");
                    counter.reserveMemory(memory);
                    awaiting.reservedMemory(awaiting.reservedMemory() + memory);
                    awaiting.savedStack().push(record.result());
                    awaiting.state(FiberState.READY);
                    readyQueue.add(awaiting);
                } catch (Throwable error) {
                    failAwaitingFiber(awaiting, error);
                }
            } else {
                failAwaitingFiber(awaiting, record.error() != null ? record.error() : new RuntimeException("Awaited task failed"));
            }
        }
    }

    private void releaseExternalTask(String scriptId, long taskId) {
        Map<Long, VeloraTask<?>> tasks = tasksByScript.get(scriptId);
        if (tasks == null) return;
        VeloraTask<?> removed = tasks.remove(taskId);
        if (removed != null) {
            ResourceCounter counter = resourcesByScript.get(scriptId);
            if (counter != null) counter.releaseTask();
        }
        if (tasks.isEmpty()) tasksByScript.remove(scriptId);
    }

    private void failAwaitingFiber(ScriptFiber fiber, Throwable error) {
        failFiber(fiber, error, io.velora.api.compiler.DiagnosticCode.RUNTIME_API_ERROR, savedLine(fiber));
        retireFiber(fiber);
    }

    private void failFiber(ScriptFiber fiber, Throwable error, io.velora.api.compiler.DiagnosticCode code, int line) {
        fiber.error(error);
        fiber.state(FiberState.FAILED);
        completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(), error);
        errorStore.record(fiber.scriptId(), new io.velora.api.debug.RuntimeError(
                fiber.scriptId(), fiber.id(), fiber.functionName(), code.name(), message(error), line, "", nanoTime.getAsLong()));
    }

    private int savedLine(ScriptFiber fiber) {
        return currentLine(fiber.savedCallStack());
    }

    private int currentLine(Deque<CallFrame> callStack) {
        CallFrame frame = callStack != null ? callStack.peek() : null;
        return frame != null ? frame.line() : 0;
    }

    private String message(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private void retireFiber(ScriptFiber fiber) {
        if (fibersById.remove(fiber.id()) == null) return;
        if (fiber.state() == FiberState.FAILED) failuresByScript.merge(fiber.scriptId(), 1L, Long::sum);
        else if (fiber.state() == FiberState.CANCELLED) cancellationsByScript.merge(fiber.scriptId(), 1L, Long::sum);
        Set<Long> ids = fibersByScript.get(fiber.scriptId());
        if (ids != null) {
            ids.remove(fiber.id());
            if (ids.isEmpty()) fibersByScript.remove(fiber.scriptId());
        }
        if (fiber.awaitTaskId() != -1) awaitingFibers.remove(fiber.awaitTaskId(), fiber.id());
        if (fiber.awaitTaskId() < -1) {
            Map<Long, VeloraTask<?>> tasks = tasksByScript.get(fiber.scriptId());
            if (tasks != null) {
                VeloraTask<?> task = tasks.remove(fiber.awaitTaskId());
                if (task != null) {
                    ResourceCounter current = resourcesByScript.get(fiber.scriptId());
                    if (current != null) current.releaseTask();
                    task.cancel();
                }
                if (tasks.isEmpty()) tasksByScript.remove(fiber.scriptId());
            }
        }
        cancellationTree.remove(fiber.id(), fiber.parentId());
        bufferedCompletions.entrySet().removeIf(entry -> entry.getValue().parentFiberId() == fiber.id());
        ResourceCounter counter = resourcesByScript.get(fiber.scriptId());
        if (counter != null) {
            counter.releaseFiber();
            counter.releaseMemory(fiber.reservedMemory());
            if (counter.fibers() == 0 && counter.tasks() == 0 && counter.memoryUsed() == 0 && counter.eventQueueSize() == 0) resourcesByScript.remove(fiber.scriptId());
        }
    }

    private void storeValue(Map<String, Map<Integer, ScriptValue>> storage, String scriptId, int index, ScriptValue value) {
        Map<Integer, ScriptValue> values = storage.computeIfAbsent(scriptId, key -> new HashMap<>());
        ScriptValue previous = values.get(index);
        long before = previous != null ? estimateValue(previous, 0) : 0;
        long after = estimateValue(value, 0);
        ResourceCounter counter = resourcesByScript.computeIfAbsent(scriptId, key -> new ResourceCounter());
        long delta = after - before;
        if (delta > 0 && exceedsMemory(counter.memoryUsed(), delta)) throw new ResourceLimitViolation("Script memory limit exceeded");
        values.put(index, value);
        adjustPersistentMemory(scriptId, counter, delta);
    }

    @Override
    public void commitStateMutation(long fiberId) {
        ScriptFiber fiber = fibersById.get(fiberId);
        if (fiber == null) return;
        String scriptId = fiber.scriptId();
        long current = persistentMemory(scriptId);
        long previous = persistentMemoryByScript.getOrDefault(scriptId, 0L);
        long delta = current - previous;
        ResourceCounter counter = resourcesByScript.computeIfAbsent(scriptId, key -> new ResourceCounter());
        if (delta > 0 && exceedsMemory(counter.memoryUsed(), delta)) throw new ResourceLimitViolation("Script memory limit exceeded");
        adjustPersistentMemory(scriptId, counter, delta);
    }

    private long persistentMemory(String scriptId) {
        long total = 0;
        Map<Integer, ScriptValue> instance = scriptInstanceFields.get(scriptId);
        if (instance != null) for (ScriptValue value : instance.values()) total = safeAdd(total, estimateValue(value, 0));
        Map<Integer, ScriptValue> statics = scriptStaticFields.get(scriptId);
        if (statics != null) for (ScriptValue value : statics.values()) total = safeAdd(total, estimateValue(value, 0));
        return total;
    }

    private void adjustPersistentMemory(String scriptId, ResourceCounter counter, long delta) {
        long current = persistentMemoryByScript.getOrDefault(scriptId, 0L);
        long next = safeAdd(current, delta);
        if (next < 0) throw new ResourceLimitViolation("Script memory accounting underflow");
        if (delta > 0) counter.reserveMemory(delta); else if (delta < 0) counter.releaseMemory(-delta);
        if (next == 0) persistentMemoryByScript.remove(scriptId); else persistentMemoryByScript.put(scriptId, next);
    }

    private long estimateValues(ScriptValue[] values) {
        long total = 0;
        IdentityHashMap<ScriptValue, Boolean> active = new IdentityHashMap<>();
        IdentityHashMap<ScriptValue, Long> estimates = new IdentityHashMap<>();
        for (ScriptValue value : values) total = safeAdd(total, estimateValue(value, 0, active, estimates));
        return total;
    }

    private long estimateValue(ScriptValue value, int depth) {
        return estimateValue(value, depth, new IdentityHashMap<>(), new IdentityHashMap<>());
    }

    private long estimateValue(ScriptValue value, int depth, IdentityHashMap<ScriptValue, Boolean> active,
                               IdentityHashMap<ScriptValue, Long> estimates) {
        if (value == null || value.isNull()) return 8;
        if (depth > limits.maxCollectionDepth()) throw new ResourceLimitViolation("Collection depth limit exceeded");
        if (value instanceof StringValue string) {
            if (limits.maxStringLength() > 0 && string.value().length() > limits.maxStringLength()) throw new ResourceLimitViolation("String length limit exceeded");
            return 40L + string.value().length() * 2L;
        }
        if (value instanceof PrimitiveValue) return 24;
        Long cached = estimates.get(value);
        if (cached != null) return cached;
        if (value instanceof ListValue || value instanceof MapValue || value instanceof SetValue || value instanceof StructValue) {
            if (active.put(value, Boolean.TRUE) != null) throw new ResourceLimitViolation("Cyclic values are not supported");
            try {
                long total;
                if (value instanceof ListValue list) {
                    if (limits.maxCollectionElements() > 0 && list.elements().size() > limits.maxCollectionElements()) throw new ResourceLimitViolation("Collection element limit exceeded");
                    total = 40L + list.elements().size() * 8L;
                    for (ScriptValue element : list.elements()) total = safeAdd(total, estimateValue(element, depth + 1, active, estimates));
                } else if (value instanceof MapValue map) {
                    if (limits.maxCollectionElements() > 0 && map.entries().size() > limits.maxCollectionElements()) throw new ResourceLimitViolation("Collection element limit exceeded");
                    total = 64L + map.entries().size() * 24L;
                    for (var entry : map.entries().entrySet()) total = safeAdd(total, safeAdd(
                            estimateValue(entry.getKey(), depth + 1, active, estimates),
                            estimateValue(entry.getValue(), depth + 1, active, estimates)));
                } else if (value instanceof SetValue set) {
                    if (limits.maxCollectionElements() > 0 && set.elements().size() > limits.maxCollectionElements()) throw new ResourceLimitViolation("Collection element limit exceeded");
                    total = 48L + set.elements().size() * 16L;
                    for (ScriptValue element : set.elements()) total = safeAdd(total, estimateValue(element, depth + 1, active, estimates));
                } else {
                    StructValue struct = (StructValue) value;
                    if (limits.maxCollectionElements() > 0 && struct.fields().size() > limits.maxCollectionElements()) throw new ResourceLimitViolation("Collection element limit exceeded");
                    total = 64L + struct.fields().size() * 24L;
                    for (ScriptValue field : struct.fields().values()) total = safeAdd(total, estimateValue(field, depth + 1, active, estimates));
                }
                estimates.put(value, total);
                return total;
            } finally {
                active.remove(value);
            }
        }
        return 64;
    }

    private boolean exceedsMemory(long used, long additional) {
        long limit = limits.memoryPerScript();
        return additional < 0 || used < 0 || used > limit || additional > limit - used;
    }

    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            throw new ResourceLimitViolation("Script memory estimate overflow");
        }
    }

    public List<ScriptFiber> fibersForScript(String scriptId) {
        Set<Long> ids = fibersByScript.get(scriptId);
        if (ids == null) return List.of();
        List<ScriptFiber> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            ScriptFiber fiber = fibersById.get(id);
            if (fiber != null) result.add(fiber);
        }
        return List.copyOf(result);
    }

    public ResourceCounter resources(String scriptId) {
        return resourcesByScript.getOrDefault(scriptId, new ResourceCounter());
    }

    public int apiCost(String scriptId) { return apiCostByScript.getOrDefault(scriptId, 0); }
    public long totalApiCost(String scriptId) { return totalApiCostByScript.getOrDefault(scriptId, 0L); }
    public long apiCalls(String scriptId) { return apiCallsByScript.getOrDefault(scriptId, 0L); }
    public long failures(String scriptId) { return failuresByScript.getOrDefault(scriptId, 0L); }
    public long cancellations(String scriptId) { return cancellationsByScript.getOrDefault(scriptId, 0L); }
    public long tickTimeNanos(String scriptId) { return tickTimeByScript.getOrDefault(scriptId, 0L); }
    public long instructionsForScript(String scriptId) { return instructionsByScript.getOrDefault(scriptId, 0L); }

    public SchedulerMetrics metrics() { return metrics; }
    public FiberQueue readyQueue() { return readyQueue; }
    public SleepQueue sleepQueue() { return sleepQueue; }
    public CompletionQueue completionQueue() { return completionQueue; }
    public CancellationTree cancellationTree() { return cancellationTree; }
    public ScriptFiber fiber(long id) { return fibersById.get(id); }
    public RuntimeErrorStore errorStore() { return errorStore; }
    private record BufferedCompletion(String scriptId, long parentFiberId, CompletionQueue.CompletionRecord record) {}

}
