package io.velora.internal.scheduler;

import io.velora.api.VeloraLimits;
import io.velora.api.function.ApiRegistry;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.task.TaskState;
import io.velora.api.task.VeloraTask;
import io.velora.api.type.VeloraType;
import io.velora.internal.bytecode.CompiledFunction;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.debug.RuntimeErrorStore;
import io.velora.internal.security.ResourceCounter;
import io.velora.internal.security.ResourceLimitViolation;
import io.velora.internal.vm.*;

import java.util.*;

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

    private final Map<String, Map<Integer, ScriptValue>> scriptInstanceFields = new HashMap<>();
    private final Map<String, Map<Integer, ScriptValue>> scriptStaticFields = new HashMap<>();
    private final Set<String> initializedScripts = new HashSet<>();
    private final Map<String, Set<Long>> fibersByScript = new HashMap<>();
    private final Map<String, Map<Long, VeloraTask<?>>> tasksByScript = new HashMap<>();
    private final Map<String, ResourceCounter> resourcesByScript = new HashMap<>();
    private final Map<String, Integer> apiCostByScript = new HashMap<>();
    private final Map<String, Long> instructionsByScript = new HashMap<>();
    private final Map<String, io.velora.internal.setting.SettingStore> settingStores = new HashMap<>();
    private final RuntimeErrorStore errorStore;
    private long currentFiberId;
    private long currentTickNanos;

    public ScriptScheduler(VeloraLimits limits, ApiRegistry apiRegistry) {
        this(limits, apiRegistry, new RuntimeErrorStore(100));
    }

    public ScriptScheduler(VeloraLimits limits, ApiRegistry apiRegistry, RuntimeErrorStore errorStore) {
        this.limits = limits;
        this.apiRegistry = apiRegistry;
        this.errorStore = errorStore;
        this.budget = new SchedulerBudget(limits);
    }

    public ScriptFiber spawnFiber(String scriptId, int functionIndex, ScriptValue[] args) {
        return spawnInternal(scriptId, functionIndex, args, -1, false, false);
    }

    public ScriptFiber spawnEventFiber(String scriptId, int functionIndex, ScriptValue[] args) {
        return spawnInternal(scriptId, functionIndex, args, -1, false, true);
    }

    private ScriptFiber spawnInternal(String scriptId, int functionIndex, ScriptValue[] args, long parentId, boolean pending, boolean eventFiber) {
        ResourceCounter counter = resourcesByScript.computeIfAbsent(scriptId, key -> new ResourceCounter());
        if (limits.maxFibersPerScript() > 0 && counter.fibers() >= limits.maxFibersPerScript()) return null;
        if (eventFiber && limits.maxEventQueuePerScript() > 0 && counter.eventQueueSize() >= limits.maxEventQueuePerScript()) return null;
        long memory;
        try { memory = estimateValues(args) + 256L; }
        catch (ResourceLimitViolation ignored) { return null; }
        if (limits.memoryPerScript() > 0 && counter.memoryUsed() + memory > limits.memoryPerScript()) return null;
        ScriptFiber fiber = new ScriptFiber(nextFiberId++, scriptId, functionIndex, args);
        fiber.parentId(parentId);
        fiber.eventFiber(eventFiber);
        fiber.reservedMemory(memory);
        fibersById.put(fiber.id(), fiber);
        fibersByScript.computeIfAbsent(scriptId, key -> new HashSet<>()).add(fiber.id());
        counter.reserveFiber();
        counter.reserveMemory(memory);
        if (eventFiber) counter.reserveEvent();
        if (parentId >= 0) cancellationTree.addChild(parentId, fiber.id());
        if (pending) pendingQueue.add(fiber); else readyQueue.add(fiber);
        return fiber;
    }

    public void spawnFiberAndAwait(String scriptId, int functionIndex, ScriptValue[] args,
                                     Map<String, CompiledModule> modules,
                                     Map<String, List<io.velora.api.setting.SettingDescriptor>> settings) {
        ScriptFiber fiber = spawnFiber(scriptId, functionIndex, args);
        if (fiber == null) return;
        while (fiber.state() != FiberState.COMPLETED && fiber.state() != FiberState.FAILED && fiber.state() != FiberState.CANCELLED) {
            tick(System.nanoTime(), modules, settings);
        }
    }

    public void spawnFiberAndAwait(String scriptId, int functionIndex, ScriptValue[] args) {
        spawnFiberAndAwait(scriptId, functionIndex, args, Map.of(), Map.of());
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
    public void sleepFiber(long fiberId, long wakeupNanos) {
        // Adjust wakeup time to use scheduler's clock, not System.nanoTime()
        long duration = wakeupNanos - System.nanoTime();
        long adjustedWakeup = currentTickNanos + Math.max(duration, 0);
        ScriptFiber fiber = fibersById.get(fiberId);
        if (fiber != null) {
            fiber.sleepUntilNanos(adjustedWakeup);
            fiber.state(FiberState.SLEEPING);
        }
    }

    @Override
    public void awaitFiber(long fiberId, long taskId) {
        ScriptFiber fiber = fibersById.get(fiberId);
        if (fiber != null) {
            fiber.awaitTaskId(taskId);
            fiber.state(FiberState.WAITING_TASK);
        }
        awaitingFibers.put(taskId, fiberId);
    }

    @Override
    public long watchTask(long fiberId, VeloraTask<?> task, VeloraType resultType) {
        ScriptFiber fiber = fibersById.get(fiberId);
        if (fiber == null) return 0;
        String scriptId = fiber.scriptId();
        ResourceCounter counter = resourcesByScript.computeIfAbsent(scriptId, key -> new ResourceCounter());
        if (limits.maxTasksPerScript() > 0 && counter.tasks() >= limits.maxTasksPerScript()) return 0;
        long taskId = nextTaskId--;
        counter.reserveTask();
        awaitFiber(fiberId, taskId);
        tasksByScript.computeIfAbsent(scriptId, key -> new HashMap<>()).put(taskId, task);
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
        return taskId;
    }

    @Override
    public boolean consumeApiCost(long fiberId, int cost) {
        ScriptFiber fiber = fibersById.get(fiberId);
        if (fiber == null) return false;
        int used = apiCostByScript.getOrDefault(fiber.scriptId(), 0);
        if (limits.apiCostPerScriptTick() > 0 && used + cost > limits.apiCostPerScriptTick()) return false;
        apiCostByScript.put(fiber.scriptId(), used + cost);
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

    public void cleanupScript(String scriptId) {
        cancelScriptTasks(scriptId);
        Set<Long> ids = new HashSet<>(fibersByScript.getOrDefault(scriptId, Set.of()));
        for (Long id : ids) {
            ScriptFiber fiber = fibersById.get(id);
            if (fiber != null) {
                fiber.state(FiberState.CANCELLED);
                retireFiber(fiber);
            }
        }
        scriptInstanceFields.remove(scriptId);
        scriptStaticFields.remove(scriptId);
        initializedScripts.remove(scriptId);
        settingStores.remove(scriptId);
        resourcesByScript.remove(scriptId);
        apiCostByScript.remove(scriptId);
        instructionsByScript.remove(scriptId);
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
        this.currentTickNanos = nowNanos;
        budget.resetTick(nowNanos);
        apiCostByScript.clear();
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

            CompiledModule module = modules.get(fiber.scriptId());
            if (module == null) {
                fiber.error(new IllegalStateException("Compiled module not found"));
                fiber.state(FiberState.FAILED);
                completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(), fiber.error());
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
                            if (!statFields.containsKey(fi.fieldIndex())) storeValue(scriptStaticFields, fiber.scriptId(), fi.fieldIndex(), fi.initialValue());
                        } else if (!instFields.containsKey(fi.fieldIndex())) {
                            storeValue(scriptInstanceFields, fiber.scriptId(), fi.fieldIndex(), fi.initialValue());
                        }
                    }
                    initializedScripts.add(fiber.scriptId());
                } catch (ResourceLimitViolation violation) {
                    fiber.error(violation);
                    fiber.state(FiberState.FAILED);
                    completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(), violation);
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
                    fiber.error(new IllegalStateException("Function not found: " + fiber.functionIndex()));
                    fiber.state(FiberState.FAILED);
                    completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(), fiber.error());
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
                            fiber.error(new RuntimeException("Runaway script: instruction limit exceeded 5 consecutive times"));
                            fiber.state(FiberState.FAILED);
                            completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(), fiber.error());
                            String funcName = module.function(fiber.functionIndex()) != null ? module.function(fiber.functionIndex()).name() : "unknown";
                            errorStore.record(fiber.scriptId(), new io.velora.api.debug.RuntimeError(
                                    fiber.scriptId(), fiber.id(), funcName,
                                    io.velora.api.compiler.DiagnosticCode.RUNTIME_RESOURCE_LIMIT.name(),
                                    "Runaway script: instruction limit exceeded 5 consecutive times", "", System.nanoTime()
                            ));
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
                fiber.error(new RuntimeException(result.error().message()));
                fiber.state(FiberState.FAILED);
                completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(), fiber.error());
                // Record error to debug store
                String funcName = module.function(fiber.functionIndex()) != null ? module.function(fiber.functionIndex()).name() : "unknown";
                errorStore.record(fiber.scriptId(), new io.velora.api.debug.RuntimeError(
                        fiber.scriptId(), fiber.id(), funcName, result.error().code().name(), result.error().message(), "", System.nanoTime()
                ));
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
        long wallTime = System.nanoTime() - nowNanos;
        metrics.recordTick(fibersExecuted, totalInstructions, wallTime);
    }

    private void drainCompletions() {
        while (!completionQueue.isEmpty()) {
            CompletionQueue.CompletionRecord record = completionQueue.poll();
            Long awaitingFiberId = awaitingFibers.remove(record.taskId());
            if (awaitingFiberId == null) continue;
            ScriptFiber awaiting = fibersById.get(awaitingFiberId);
            if (awaiting == null) continue;
            if (record.taskId() < -1) releaseExternalTask(awaiting.scriptId(), record.taskId());
            if (awaiting.savedStack() == null || awaiting.isDone()) continue;
            awaiting.awaitTaskId(-1);
            if (record.success()) {
                try {
                    long memory = estimateValue(record.result(), 0);
                    ResourceCounter counter = resourcesByScript.computeIfAbsent(awaiting.scriptId(), key -> new ResourceCounter());
                    if (limits.memoryPerScript() > 0 && counter.memoryUsed() + memory > limits.memoryPerScript()) throw new ResourceLimitViolation("Script memory limit exceeded");
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
        fiber.error(error);
        fiber.state(FiberState.FAILED);
        completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(), error);
        retireFiber(fiber);
    }

    private void retireFiber(ScriptFiber fiber) {
        if (fibersById.remove(fiber.id()) == null) return;
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
        ResourceCounter counter = resourcesByScript.get(fiber.scriptId());
        if (counter != null) {
            counter.releaseFiber();
            counter.releaseMemory(fiber.reservedMemory());
            if (fiber.eventFiber()) counter.releaseEvent();
            if (counter.fibers() == 0 && counter.tasks() == 0 && counter.memoryUsed() == 0 && counter.eventQueueSize() == 0) resourcesByScript.remove(fiber.scriptId());
        }
    }

    private void storeValue(Map<String, Map<Integer, ScriptValue>> storage, String scriptId, int index, ScriptValue value) {
        Map<Integer, ScriptValue> values = storage.computeIfAbsent(scriptId, key -> new HashMap<>());
        ScriptValue previous = values.get(index);
        long before = estimateValue(previous, 0);
        long after = estimateValue(value, 0);
        ResourceCounter counter = resourcesByScript.computeIfAbsent(scriptId, key -> new ResourceCounter());
        long delta = after - before;
        if (delta > 0 && limits.memoryPerScript() > 0 && counter.memoryUsed() + delta > limits.memoryPerScript()) throw new ResourceLimitViolation("Script memory limit exceeded");
        values.put(index, value);
        if (delta > 0) counter.reserveMemory(delta); else if (delta < 0) counter.releaseMemory(-delta);
    }

    private long estimateValues(ScriptValue[] values) {
        long total = 0;
        for (ScriptValue value : values) total += estimateValue(value, 0);
        return total;
    }

    private long estimateValue(ScriptValue value, int depth) {
        if (value == null || value.isNull()) return 8;
        if (depth > limits.maxCollectionDepth()) throw new ResourceLimitViolation("Collection depth limit exceeded");
        if (value instanceof StringValue string) {
            if (limits.maxStringLength() > 0 && string.value().length() > limits.maxStringLength()) throw new ResourceLimitViolation("String length limit exceeded");
            return 40L + string.value().length() * 2L;
        }
        if (value instanceof PrimitiveValue) return 24;
        if (value instanceof ListValue list) {
            if (limits.maxCollectionElements() > 0 && list.elements().size() > limits.maxCollectionElements()) throw new ResourceLimitViolation("Collection element limit exceeded");
            long total = 40L + list.elements().size() * 8L;
            for (ScriptValue element : list.elements()) total += estimateValue(element, depth + 1);
            return total;
        }
        if (value instanceof MapValue map) {
            if (limits.maxCollectionElements() > 0 && map.entries().size() > limits.maxCollectionElements()) throw new ResourceLimitViolation("Collection element limit exceeded");
            long total = 64L + map.entries().size() * 24L;
            for (var entry : map.entries().entrySet()) total += estimateValue(entry.getKey(), depth + 1) + estimateValue(entry.getValue(), depth + 1);
            return total;
        }
        if (value instanceof SetValue set) {
            if (limits.maxCollectionElements() > 0 && set.elements().size() > limits.maxCollectionElements()) throw new ResourceLimitViolation("Collection element limit exceeded");
            long total = 48L + set.elements().size() * 16L;
            for (ScriptValue element : set.elements()) total += estimateValue(element, depth + 1);
            return total;
        }
        if (value instanceof StructValue struct) {
            if (limits.maxCollectionElements() > 0 && struct.fields().size() > limits.maxCollectionElements()) throw new ResourceLimitViolation("Collection element limit exceeded");
            long total = 64L + struct.fields().size() * 24L;
            for (ScriptValue field : struct.fields().values()) total += estimateValue(field, depth + 1);
            return total;
        }
        return 64;
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
    public long instructionsForScript(String scriptId) { return instructionsByScript.getOrDefault(scriptId, 0L); }

    public SchedulerMetrics metrics() { return metrics; }
    public FiberQueue readyQueue() { return readyQueue; }
    public SleepQueue sleepQueue() { return sleepQueue; }
    public CompletionQueue completionQueue() { return completionQueue; }
    public CancellationTree cancellationTree() { return cancellationTree; }
    public ScriptFiber fiber(long id) { return fibersById.get(id); }
    public RuntimeErrorStore errorStore() { return errorStore; }
}
