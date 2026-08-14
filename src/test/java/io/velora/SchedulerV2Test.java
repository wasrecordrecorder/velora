package io.velora;

import io.velora.api.VeloraLimits;
import io.velora.api.function.*;
import io.velora.api.type.VeloraTypes;
import io.velora.internal.bytecode.*;
import io.velora.internal.ir.*;
import io.velora.internal.lexer.*;
import io.velora.internal.parser.*;
import io.velora.internal.scheduler.*;
import io.velora.internal.semantic.*;
import io.velora.internal.runtime.*;
import io.velora.internal.registry.*;
import io.velora.internal.compiler.*;
import io.velora.internal.script.*;
import io.velora.internal.setting.*;
import io.velora.internal.vm.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class SchedulerV2Test {

    private DefaultTypeRegistry typeRegistry;
    private DefaultSettingRegistry settingRegistry;
    private DefaultPermissionRegistry permissionRegistry;
    private DefaultConstantRegistry constantRegistry;
    private DefaultApiRegistry apiRegistry;

    @BeforeEach
    void setUp() {
        typeRegistry = new DefaultTypeRegistry();
        settingRegistry = new DefaultSettingRegistry();
        permissionRegistry = new DefaultPermissionRegistry();
        constantRegistry = new DefaultConstantRegistry();
        apiRegistry = new DefaultApiRegistry(new DefaultTypeRegistry());
    }

    private CompiledModule compile(String source) {
        ParseResult parseResult = Parser.parse(source, "main.vls");
        assertNotNull(parseResult.scriptNode());
        SemanticAnalyzer analyzer = new SemanticAnalyzer(
                typeRegistry, settingRegistry, apiRegistry, constantRegistry, permissionRegistry);
        ResolvedScript resolved = analyzer.analyze(parseResult.scriptNode());
        IrModule irModule = new IrBuilder(resolved, apiRegistry).build();
        return new BytecodeWriter().write(irModule);
    }

    private Map<String, CompiledModule> modules(CompiledModule m) {
        return Map.of("T", m);
    }

    private Map<String, List<io.velora.api.setting.SettingDescriptor>> emptySettings() {
        return Map.of("T", List.of());
    }

    // === Fiber Lifecycle ===

    @Test
    @DisplayName("Fiber starts in READY state")
    void fiberStartsReady() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T {\n    int run() { return 42 }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        assertEquals(FiberState.READY, fiber.state());
        assertEquals(1, fiber.id());
        assertEquals("T", fiber.scriptId());
    }

    @Test
    @DisplayName("Fiber completes after tick")
    void fiberCompletes() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T {\n    int run() { return 42 }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        scheduler.tick(System.nanoTime(), modules(m), emptySettings());
        assertTrue(fiber.isDone(), "Fiber should be done. State: " + fiber.state());
        assertEquals(FiberState.COMPLETED, fiber.state());
    }

    @Test
    @DisplayName("Fiber with args")
    void fiberWithArgs() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T {\n    int run(int n) { return n + 1 }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[]{PrimitiveValue.of(41)});
        scheduler.tick(System.nanoTime(), modules(m), emptySettings());
        assertTrue(fiber.isDone());
    }

    // === Delay ===

    @Test
    @DisplayName("Delay puts fiber to sleep")
    void delaySleeps() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T {\n    async void run() { delay(1000000000) }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        long now = System.nanoTime();
        scheduler.tick(now, modules(m), emptySettings());
        assertEquals(FiberState.SLEEPING, fiber.state());
    }

    @Test
    @DisplayName("Fiber wakes after delay completes")
    void delayWakes() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T {\n    async void run() { delay(1000000000) }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        long now = System.nanoTime();
        scheduler.tick(now, modules(m), emptySettings());
        assertEquals(FiberState.SLEEPING, fiber.state());
        scheduler.tick(now + 2_000_000_000L, modules(m), emptySettings());
        assertTrue(fiber.isDone(), "Fiber should be done after waking");
    }

    @Test
    @DisplayName("Injected runtime clock drives delay scheduling")
    void injectedClockDrivesDelayScheduling() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T { async void run() { delay(100) } }");
        AtomicLong now = new AtomicLong(1_000);
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry,
                new io.velora.internal.debug.RuntimeErrorStore(10), null, constantRegistry, typeRegistry, now::get);
        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        assertEquals(1_000L, fiber.createdAtNanos());
        scheduler.tick(now.get(), modules(m), emptySettings());
        assertEquals(FiberState.SLEEPING, fiber.state());
        assertEquals(1_100L, fiber.sleepUntilNanos());
        now.set(1_099);
        scheduler.tick(now.get(), modules(m), emptySettings());
        assertEquals(FiberState.SLEEPING, fiber.state());
        now.set(1_100);
        scheduler.tick(now.get(), modules(m), emptySettings());
        assertTrue(fiber.isDone());
    }

    @Test
    @DisplayName("Short delay completes quickly")
    void shortDelay() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T {\n    async void run() { delay(1) }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        long now = System.nanoTime();
        scheduler.tick(now, modules(m), emptySettings());
        scheduler.tick(now + 100, modules(m), emptySettings());
        assertTrue(fiber.isDone());
    }

    // === Spawn + Await ===

    @Test
    @DisplayName("Spawn child and await result")
    void spawnAwait() {
        CompiledModule m = compile("""
            @Script(name="T", version="1")
            script T {
                int child() { return 42 }
                async int run() { Task<int> r = spawn child()
                    return await(r) }
            }
            """);
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        CompiledFunction runFn = m.functionByName("run");
        ScriptFiber parent = scheduler.spawnFiber("T", runFn.index(), new ScriptValue[0]);
        long now = System.nanoTime();

        scheduler.tick(now, modules(m), emptySettings());
        assertEquals(FiberState.WAITING_TASK, parent.state(), "Parent should wait for child");

        scheduler.tick(now + 1, modules(m), emptySettings());
        assertTrue(parent.isDone(), "Parent should complete after child");
    }

    @Test
    @DisplayName("Completed spawned tasks remain awaitable")
    void completedSpawnedTasksRemainAwaitable() {
        CompiledModule m = compile("""
            @Script(name="T", version="1")
            script T {
                int first() { return 20 }
                int second() { return 22 }
                async int run() {
                    Task<int> a = spawn first()
                    Task<int> b = spawn second()
                    return await(a) + await(b)
                }
            }
            """);
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber parent = scheduler.spawnFiber("T", m.functionByName("run").index(), new ScriptValue[0]);
        long now = System.nanoTime();
        scheduler.tick(now, modules(m), emptySettings());
        scheduler.tick(now + 1, modules(m), emptySettings());
        scheduler.tick(now + 2, modules(m), emptySettings());
        assertTrue(parent.isDone());
        assertEquals(42, ((Number) parent.result().boxed()).intValue());
    }

    @Test
    @DisplayName("Spawn child without await - child runs independently")
    void spawnNoAwait() {
        CompiledModule m = compile("""
            @Script(name="T", version="1")
            script T {
                int child() { return 42 }
                async void run() { spawn child() }
            }
            """);
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        CompiledFunction runFn = m.functionByName("run");
        ScriptFiber parent = scheduler.spawnFiber("T", runFn.index(), new ScriptValue[0]);
        long now = System.nanoTime();

        scheduler.tick(now, modules(m), emptySettings());
        assertTrue(parent.isDone(), "Parent should complete without waiting");
    }

    // === Cancellation ===

    @Test
    @DisplayName("Cancel sleeping fiber")
    void cancelSleeping() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T {\n    async void run() { delay(999999999999) }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        long now = System.nanoTime();
        scheduler.tick(now, modules(m), emptySettings());
        assertEquals(FiberState.SLEEPING, fiber.state());

        scheduler.cancelFiber(fiber.id());
        scheduler.tick(now + 1, modules(m), emptySettings());
        assertTrue(fiber.isDone(), "Fiber should be done after cancellation");
        assertEquals(1L, scheduler.cancellations("T"));
    }

    @Test
    @DisplayName("Cancel ready fiber before execution")
    void cancelReady() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T {\n    async void run() { delay(999999999999) }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        scheduler.cancelFiber(fiber.id());
        scheduler.tick(System.nanoTime(), modules(m), emptySettings());
        assertTrue(fiber.isDone());
    }

    // === Multiple Fibers ===

    @Test
    @DisplayName("Multiple fibers execute in same tick")
    void multipleFibers() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T {\n    int run() { return 42 }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber f1 = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        ScriptFiber f2 = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        ScriptFiber f3 = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        scheduler.tick(System.nanoTime(), modules(m), emptySettings());
        assertTrue(f1.isDone());
        assertTrue(f2.isDone());
        assertTrue(f3.isDone());
    }

    @Test
    @DisplayName("Fiber IDs are unique and sequential")
    void fiberIds() {
        CompiledModule m = compile("@Script(name=\"T\", version=\"1\")\nscript T {\n    int run() { return 42 }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber f1 = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        ScriptFiber f2 = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        assertNotEquals(f1.id(), f2.id());
        assertTrue(f2.id() > f1.id());
    }

    // === Missing Module ===

    @Test
    @DisplayName("Fiber with missing module fails")
    void missingModuleFails() {
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        ScriptFiber fiber = scheduler.spawnFiber("nonexistent", 0, new ScriptValue[0]);
        scheduler.tick(System.nanoTime(), Map.of(), Map.of());
        assertTrue(fiber.isDone());
        assertEquals(FiberState.FAILED, fiber.state());
        assertEquals(1L, scheduler.failures("nonexistent"));
    }

    // === FiberState enum ===

    @Test
    @DisplayName("FiberState has all expected states")
    void fiberStates() {
        assertNotNull(FiberState.valueOf("READY"));
        assertNotNull(FiberState.valueOf("RUNNING"));
        assertNotNull(FiberState.valueOf("WAITING_TASK"));
        assertNotNull(FiberState.valueOf("SLEEPING"));
        assertNotNull(FiberState.valueOf("PAUSED"));
        assertNotNull(FiberState.valueOf("COMPLETED"));
        assertNotNull(FiberState.valueOf("FAILED"));
        assertNotNull(FiberState.valueOf("CANCELLED"));
    }

    @Test
    @DisplayName("isDone returns true for terminal states")
    void fiberIsDone() {
        ScriptFiber fiber = new ScriptFiber(1, "T", 0, new ScriptValue[0]);
        assertFalse(fiber.isDone());
        fiber.state(FiberState.COMPLETED);
        assertTrue(fiber.isDone());
        fiber.state(FiberState.FAILED);
        assertTrue(fiber.isDone());
        fiber.state(FiberState.CANCELLED);
        assertTrue(fiber.isDone());
        fiber.state(FiberState.READY);
        assertFalse(fiber.isDone());
    }
}
