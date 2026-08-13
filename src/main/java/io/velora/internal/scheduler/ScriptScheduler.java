package io.velora.internal.scheduler;

import io.velora.api.VeloraLimits;
import io.velora.api.function.ApiRegistry;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.task.TaskState;
import io.velora.api.task.VeloraTask;
import io.velora.internal.bytecode.CompiledFunction;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.debug.RuntimeErrorStore;
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

    private final Map<Long, ScriptFiber> fibersById = new HashMap<>();
    private final Map<Long, Long> awaitingFibers = new HashMap<>();

    private final Map<String, Map<Integer, ScriptValue>> scriptInstanceFields = new HashMap<>();
    private final Map<String, Map<Integer, ScriptValue>> scriptStaticFields = new HashMap<>();
    private final Set<String> initializedScripts = new HashSet<>();
    private final Map<String, java.util.Set<Long>> fibersByScript = new HashMap<>();
    private final Map<String, Map<Long, VeloraTask<?>>> tasksByScript = new HashMap<>();
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
        if (limits.maxFibersPerScript() > 0) {
            java.util.Set<Long> scriptFibers = fibersByScript.computeIfAbsent(scriptId, k -> new java.util.HashSet<>());
            // Count only non-completed fibers
            long active = scriptFibers.stream().filter(id -> {
                ScriptFiber f = fibersById.get(id);
                return f != null && f.state() != FiberState.COMPLETED && f.state() != FiberState.FAILED && f.state() != FiberState.CANCELLED;
            }).count();
            if (active >= limits.maxFibersPerScript()) {
                return null;
            }
        }
        ScriptFiber fiber = new ScriptFiber(nextFiberId++, scriptId, functionIndex, args);
        fibersById.put(fiber.id(), fiber);
        fibersByScript.computeIfAbsent(scriptId, k -> new java.util.HashSet<>()).add(fiber.id());
        readyQueue.add(fiber);
        return fiber;
    }

    /**
     * Spawns a fiber and runs the scheduler until it completes.
     * Used for synchronous execution of lifecycle hooks like onLoad.
     */
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
        if (limits.maxFibersPerScript() > 0) {
            java.util.Set<Long> scriptFibers = fibersByScript.computeIfAbsent(scriptId, k -> new java.util.HashSet<>());
            long active = scriptFibers.stream().filter(id -> {
                ScriptFiber f = fibersById.get(id);
                return f != null && f.state() != FiberState.COMPLETED && f.state() != FiberState.FAILED && f.state() != FiberState.CANCELLED;
            }).count();
            if (active >= limits.maxFibersPerScript()) {
                return -1;
            }
        }
        ScriptFiber fiber = new ScriptFiber(nextFiberId++, scriptId, functionIndex, args);
        fiber.parentId(parentId);
        fibersById.put(fiber.id(), fiber);
        fibersByScript.computeIfAbsent(scriptId, k -> new java.util.HashSet<>()).add(fiber.id());
        cancellationTree.addChild(parentId, fiber.id());
        pendingQueue.add(fiber);
        return fiber.id();
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
    public void watchTask(long fiberId, long taskId, VeloraTask<?> task) {
        awaitFiber(fiberId, taskId);
        ScriptFiber fiber = fibersById.get(fiberId);
        String scriptId = fiber != null ? fiber.scriptId() : null;
        if (scriptId != null) {
            tasksByScript.computeIfAbsent(scriptId, k -> new HashMap<>()).put(taskId, task);
        }
        task.onComplete(t -> {
            ScriptValue result = t.state() == TaskState.SUCCEEDED
                    ? VirtualMachine.javaToValue(t.result())
                    : PrimitiveValue.nullValue();
            completionQueue.offer(taskId, t.state() == TaskState.SUCCEEDED, result, t.failure());
            if (scriptId != null) {
                Map<Long, VeloraTask<?>> tasks = tasksByScript.get(scriptId);
                if (tasks != null) tasks.remove(taskId);
            }
        });
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
        scriptInstanceFields.remove(scriptId);
        scriptStaticFields.remove(scriptId);
        initializedScripts.remove(scriptId);
        tasksByScript.remove(scriptId);
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
            scriptInstanceFields.computeIfAbsent(fiber.scriptId(), k -> new HashMap<>()).put(fieldIndex, value);
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
            scriptStaticFields.computeIfAbsent(fiber.scriptId(), k -> new HashMap<>()).put(fieldIndex, value);
        }
    }

    public ScriptValue loadFieldForScript(String scriptId, int fieldIndex) {
        Map<Integer, ScriptValue> fields = scriptInstanceFields.get(scriptId);
        if (fields != null) return fields.getOrDefault(fieldIndex, PrimitiveValue.nullValue());
        return PrimitiveValue.nullValue();
    }

    public void storeFieldForScript(String scriptId, int fieldIndex, ScriptValue value) {
        scriptInstanceFields.computeIfAbsent(scriptId, k -> new HashMap<>()).put(fieldIndex, value);
    }

    public ScriptValue loadStaticForScript(String scriptId, int fieldIndex) {
        Map<Integer, ScriptValue> fields = scriptStaticFields.get(scriptId);
        if (fields != null) return fields.getOrDefault(fieldIndex, PrimitiveValue.nullValue());
        return PrimitiveValue.nullValue();
    }

    public void storeStaticForScript(String scriptId, int fieldIndex, ScriptValue value) {
        scriptStaticFields.computeIfAbsent(scriptId, k -> new HashMap<>()).put(fieldIndex, value);
    }

    public void tick(long nowNanos, Map<String, CompiledModule> modules, Map<String, List<SettingDescriptor>> scriptSettings) {
        this.currentTickNanos = nowNanos;
        budget.resetTick(nowNanos);

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
            completionQueue.offer(f.id(), false, PrimitiveValue.nullValue(),
                    new RuntimeException("Cancelled"));
        }

        while (!completionQueue.isEmpty()) {
            CompletionQueue.CompletionRecord rec = completionQueue.poll();
            Long awaitingFiberId = awaitingFibers.remove(rec.taskId());
            if (awaitingFiberId != null) {
                ScriptFiber awaiting = fibersById.get(awaitingFiberId);
                if (awaiting != null && awaiting.savedStack() != null) {
                    awaiting.savedStack().push(rec.success() ? rec.result() : PrimitiveValue.nullValue());
                    awaiting.state(FiberState.READY);
                    readyQueue.add(awaiting);
                }
            }
        }

        int fibersExecuted = 0;
        int totalInstructions = 0;

        while (!readyQueue.isEmpty() && !budget.wallTimeExceeded(System.nanoTime())) {
            ScriptFiber fiber = readyQueue.poll();
            fiber.instructionsThisTick(0);
            if (cancellationTree.isCancelled(fiber.id())) {
                fiber.state(FiberState.CANCELLED);
                completionQueue.offer(fiber.id(), false, PrimitiveValue.nullValue(),
                        new RuntimeException("Cancelled"));
                continue;
            }

            CompiledModule module = modules.get(fiber.scriptId());
            if (module == null) {
                fiber.state(FiberState.FAILED);
                continue;
            }

            // Initialize fields from module field initializers on first use
            if (!initializedScripts.contains(fiber.scriptId())) {
                initializedScripts.add(fiber.scriptId());
                Map<Integer, ScriptValue> instFields = scriptInstanceFields.computeIfAbsent(fiber.scriptId(), k -> new HashMap<>());
                Map<Integer, ScriptValue> statFields = scriptStaticFields.computeIfAbsent(fiber.scriptId(), k -> new HashMap<>());
                for (CompiledModule.FieldInitializer fi : module.fieldInitializers()) {
                    if (fi.isStatic()) {
                        statFields.putIfAbsent(fi.fieldIndex(), fi.initialValue());
                    } else {
                        instFields.putIfAbsent(fi.fieldIndex(), fi.initialValue());
                    }
                }
            }

            currentFiberId = fiber.id();

            List<SettingDescriptor> settings = scriptSettings.getOrDefault(fiber.scriptId(), List.of());
            io.velora.internal.setting.SettingStore store = settingStores.get(fiber.scriptId());
            VirtualMachine vm = new VirtualMachine(apiRegistry, settings, store, budget.remainingFiberInstructions(fiber),
                    limits.maxCallDepth(), limits.maxStringLength());
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
                    fiber.state(FiberState.FAILED);
                    continue;
                }
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

            // Drain completion queue to resume any parents awaiting this fiber
            while (!completionQueue.isEmpty()) {
                CompletionQueue.CompletionRecord rec = completionQueue.poll();
                Long awaitingFiberId = awaitingFibers.remove(rec.taskId());
                if (awaitingFiberId != null) {
                    ScriptFiber awaiting = fibersById.get(awaitingFiberId);
                    if (awaiting != null && awaiting.savedStack() != null) {
                        awaiting.savedStack().push(rec.success() ? rec.result() : PrimitiveValue.nullValue());
                        awaiting.state(FiberState.READY);
                        readyQueue.add(awaiting);
                    }
                }
            }

            fibersExecuted++;
            totalInstructions += result.instructionsExecuted();
            budget.recordInstructions(result.instructionsExecuted());
            fiber.instructionsThisTick(fiber.instructionsThisTick() + result.instructionsExecuted());
        }

        long wallTime = System.nanoTime() - nowNanos;
        metrics.recordTick(fibersExecuted, totalInstructions, wallTime);
    }

    public SchedulerMetrics metrics() { return metrics; }
    public FiberQueue readyQueue() { return readyQueue; }
    public SleepQueue sleepQueue() { return sleepQueue; }
    public CompletionQueue completionQueue() { return completionQueue; }
    public CancellationTree cancellationTree() { return cancellationTree; }
    public ScriptFiber fiber(long id) { return fibersById.get(id); }
    public RuntimeErrorStore errorStore() { return errorStore; }
}
