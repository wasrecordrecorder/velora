package io.velora;

import io.velora.api.event.EventConcurrency;
import io.velora.api.event.EventDescriptor;
import io.velora.api.event.EventKey;
import io.velora.api.event.EventOverflowPolicy;
import io.velora.api.type.VeloraTypes;
import io.velora.internal.event.DefaultEventRegistry;
import io.velora.host.MainThreadExecutor;
import io.velora.host.VeloraClock;
import io.velora.host.VeloraFileSystem;
import io.velora.host.VeloraHost;
import io.velora.host.VeloraLogger;
import io.velora.host.WorkerExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventRuntimeV2Test {
    @Test
    void everyOverflowPolicyHasDeterministicQueueSemantics() {
        assertOverflow(EventOverflowPolicy.DROP_NEWEST, List.of(1, 2), false);
        assertOverflow(EventOverflowPolicy.DROP_OLDEST, List.of(2, 3), false);
        assertOverflow(EventOverflowPolicy.KEEP_LATEST, List.of(3), false);
        assertOverflow(EventOverflowPolicy.COALESCE, List.of(1, 5), false);
        assertOverflow(EventOverflowPolicy.FAIL_SCRIPT, List.of(1, 2), true);
    }

    @Test
    void inboundConcurrencyPoliciesCoalesceOrDropBeforeDispatch() {
        assertConcurrency(EventConcurrency.DROP, List.of(1));
        assertConcurrency(EventConcurrency.LATEST, List.of(3));
        assertConcurrency(EventConcurrency.RESTART, List.of(3));
        assertConcurrency(EventConcurrency.QUEUE, List.of(1, 2, 3));
        assertConcurrency(EventConcurrency.PARALLEL, List.of(1, 2, 3));
    }

    @Test
    void failScriptOverflowIsMarshalledToMainThread() {
        List<Runnable> mainActions = new ArrayList<>();
        VeloraHost host = new VeloraHost() {
            @Override public String id() { return "event-test"; }
            @Override public String version() { return "1"; }
            @Override public MainThreadExecutor mainThread() { return new MainThreadExecutor() {
                @Override public boolean isMainThread() { return false; }
                @Override public void execute(Runnable action) { mainActions.add(action); }
            }; }
            @Override public WorkerExecutor workers() { return new WorkerExecutor() {
                @Override public void execute(Runnable action) { action.run(); }
                @Override public void shutdown() {}
            }; }
            @Override public VeloraClock clock() { return new VeloraClock() {
                @Override public long nanoTime() { return System.nanoTime(); }
                @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
            }; }
            @Override public VeloraLogger logger() { return new VeloraLogger() {
                @Override public void debug(String message) {}
                @Override public void info(String message) {}
                @Override public void warn(String message) {}
                @Override public void error(String message, Throwable error) {}
            }; }
            @Override public VeloraFileSystem fileSystem() { return null; }
        };
        DefaultEventRegistry events = new DefaultEventRegistry(host);
        events.register(EventDescriptor.builder("overflow.fail")
                .scriptName("OverflowFail")
                .payloadType(VeloraTypes.INT)
                .queueLimit(1)
                .overflowPolicy(EventOverflowPolicy.FAIL_SCRIPT)
                .build());
        List<String> failed = new ArrayList<>();
        events.setOverflowHandler(failed::add);
        EventKey<Integer> key = EventKey.of("overflow.fail", Integer.class);
        events.emitSafe(key, 1);
        events.emitSafe(key, 2);
        assertEquals(List.of(), failed);
        for (Runnable action : List.copyOf(mainActions)) action.run();
        assertEquals(List.of("overflow.fail"), failed);
    }

    @Test
    void runningHandlerQueueUsesConfiguredPayloadCoalescer() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("velora-event-coalesce-");
        List<Integer> recorded = new ArrayList<>();
        VeloraHost host = new VeloraHost() {
            @Override public String id() { return "event-runtime"; }
            @Override public String version() { return "1"; }
            @Override public MainThreadExecutor mainThread() { return new MainThreadExecutor() {
                @Override public boolean isMainThread() { return true; }
                @Override public void execute(Runnable action) { action.run(); }
            }; }
            @Override public WorkerExecutor workers() { return new WorkerExecutor() {
                @Override public void execute(Runnable action) { action.run(); }
                @Override public void shutdown() {}
            }; }
            @Override public VeloraClock clock() { return new VeloraClock() {
                @Override public long nanoTime() { return System.nanoTime(); }
                @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
            }; }
            @Override public VeloraLogger logger() { return new VeloraLogger() {
                @Override public void debug(String message) {}
                @Override public void info(String message) {}
                @Override public void warn(String message) {}
                @Override public void error(String message, Throwable error) {}
            }; }
            @Override public VeloraFileSystem fileSystem() { return VeloraFileSystem.local(root); }
        };
        io.velora.api.VeloraEngine engine = io.velora.api.Velora.builder().host(host).build();
        engine.api().namespace("probe", ns -> ns.function("record", VeloraTypes.UNIT, p -> p.required("value", VeloraTypes.INT), ctx -> {
            recorded.add(ctx.argument(0, int.class));
            return null;
        }));
        engine.events().register(EventDescriptor.builder("runtime.sum")
                .scriptName("Sum")
                .payloadType(VeloraTypes.INT)
                .defaultConcurrency(EventConcurrency.QUEUE)
                .queueLimit(1)
                .overflowPolicy(EventOverflowPolicy.COALESCE)
                .coalescer((left, right) -> (Integer) left + (Integer) right)
                .build());
        engine.freeze();
        String source = "@Script(\"EventQueue\")\n@Version(\"1\")\nscript EventQueue { @Sum async Sum(int value) { delay(10.milliseconds)\n probe.record(value) } }";
        assertEquals(true, engine.scripts().create(io.velora.api.script.ScriptCreateRequest.builder("event-queue", "EventQueue").file("main.vls", source).build()).success());
        assertEquals(true, engine.scripts().enable("event-queue").success());
        EventKey<Integer> key = EventKey.of("runtime.sum", Integer.class);
        engine.events().emitSafe(key, 1);
        engine.tick();
        engine.events().emitSafe(key, 2);
        engine.tick();
        engine.events().emitSafe(key, 3);
        engine.tick();
        Thread.sleep(15);
        engine.tick();
        engine.tick();
        Thread.sleep(15);
        engine.tick();
        engine.tick();
        assertEquals(List.of(1, 5), recorded);
        var profile = engine.debug().profiler("event-queue");
        assertEquals(1L, profile.coalescedEvents());
        assertEquals(1, profile.maxQueueDepth());
        assertEquals(0, profile.eventQueueDepth());
        assertEquals(2L, profile.apiCalls());
        engine.close();
    }

    @Test
    void disablingScriptCancelsRunningEventFibers() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("velora-event-disable-");
        List<String> output = new ArrayList<>();
        VeloraHost host = new VeloraHost() {
            @Override public String id() { return "event-disable"; }
            @Override public String version() { return "1"; }
            @Override public MainThreadExecutor mainThread() { return new MainThreadExecutor() {
                @Override public boolean isMainThread() { return true; }
                @Override public void execute(Runnable action) { action.run(); }
            }; }
            @Override public WorkerExecutor workers() { return new WorkerExecutor() {
                @Override public void execute(Runnable action) { action.run(); }
                @Override public void shutdown() {}
            }; }
            @Override public VeloraClock clock() { return new VeloraClock() {
                @Override public long nanoTime() { return System.nanoTime(); }
                @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
            }; }
            @Override public VeloraLogger logger() { return new VeloraLogger() {
                @Override public void debug(String message) {}
                @Override public void info(String message) { output.add(message); }
                @Override public void warn(String message) {}
                @Override public void error(String message, Throwable error) {}
            }; }
            @Override public VeloraFileSystem fileSystem() { return VeloraFileSystem.local(root); }
        };
        io.velora.api.VeloraEngine engine = io.velora.api.Velora.builder().host(host).build();
        engine.events().register(EventDescriptor.builder("runtime.stop")
                .scriptName("StopEvent")
                .payloadType(VeloraTypes.UNIT)
                .defaultConcurrency(EventConcurrency.QUEUE)
                .build());
        engine.freeze();
        String source = "@Script(\"StopEventScript\")\n@Version(\"1\")\nscript StopEventScript { @StopEvent async StopEvent() { delay(20.milliseconds)\n console.print(\"event-after-disable\") } }";
        assertEquals(true, engine.scripts().create(io.velora.api.script.ScriptCreateRequest.builder("stop-event", "StopEventScript").file("main.vls", source).build()).success());
        assertEquals(true, engine.scripts().enable("stop-event").success());
        engine.events().emitSafe(EventKey.of("runtime.stop", Void.class), null);
        engine.tick();
        assertEquals(true, engine.scripts().disable("stop-event").success());
        Thread.sleep(25);
        engine.tick();
        assertEquals(0L, output.stream().filter("event-after-disable"::equals).count());
        assertEquals(1L, engine.debug().profiler("stop-event").cancellations());
        engine.close();
    }

    @Test
    void runningUnitEventCanCoalesceWithoutPayloadIndexing() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("velora-event-unit-coalesce-");
        List<String> output = new ArrayList<>();
        VeloraHost host = new VeloraHost() {
            @Override public String id() { return "event-unit-runtime"; }
            @Override public String version() { return "1"; }
            @Override public MainThreadExecutor mainThread() { return new MainThreadExecutor() {
                @Override public boolean isMainThread() { return true; }
                @Override public void execute(Runnable action) { action.run(); }
            }; }
            @Override public WorkerExecutor workers() { return new WorkerExecutor() {
                @Override public void execute(Runnable action) { action.run(); }
                @Override public void shutdown() {}
            }; }
            @Override public VeloraClock clock() { return new VeloraClock() {
                @Override public long nanoTime() { return System.nanoTime(); }
                @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
            }; }
            @Override public VeloraLogger logger() { return new VeloraLogger() {
                @Override public void debug(String message) {}
                @Override public void info(String message) { output.add(message); }
                @Override public void warn(String message) {}
                @Override public void error(String message, Throwable error) {}
            }; }
            @Override public VeloraFileSystem fileSystem() { return VeloraFileSystem.local(root); }
        };
        io.velora.api.VeloraEngine engine = io.velora.api.Velora.builder().host(host).build();
        engine.events().register(EventDescriptor.builder("runtime.unit")
                .scriptName("UnitEvent")
                .payloadType(VeloraTypes.UNIT)
                .defaultConcurrency(EventConcurrency.QUEUE)
                .queueLimit(1)
                .overflowPolicy(EventOverflowPolicy.COALESCE)
                .coalescer((left, right) -> null)
                .build());
        engine.freeze();
        String source = "@Script(\"UnitQueue\")\n@Version(\"1\")\nscript UnitQueue { @UnitEvent async UnitEvent() { delay(10.milliseconds)\n console.print(\"unit-event\") } }";
        assertEquals(true, engine.scripts().create(io.velora.api.script.ScriptCreateRequest.builder("unit-queue", "UnitQueue").file("main.vls", source).build()).success());
        assertEquals(true, engine.scripts().enable("unit-queue").success());
        EventKey<Void> key = EventKey.of("runtime.unit", Void.class);
        engine.events().emitSafe(key, null);
        engine.tick();
        engine.events().emitSafe(key, null);
        engine.tick();
        engine.events().emitSafe(key, null);
        engine.tick();
        Thread.sleep(15);
        engine.tick();
        engine.tick();
        Thread.sleep(15);
        engine.tick();
        engine.tick();
        assertEquals(2L, output.stream().filter("unit-event"::equals).count());
        assertEquals(List.of(), engine.debug().errors("unit-queue"));
        engine.close();
    }

    private void assertOverflow(EventOverflowPolicy policy, List<Integer> expected, boolean shouldFail) {
        DefaultEventRegistry events = new DefaultEventRegistry();
        String id = "overflow." + policy.name().toLowerCase();
        EventDescriptor.Builder builder = EventDescriptor.builder(id)
                .scriptName("Overflow" + policy.name())
                .payloadType(VeloraTypes.INT)
                .defaultConcurrency(EventConcurrency.QUEUE)
                .queueLimit(2)
                .overflowPolicy(policy);
        if (policy == EventOverflowPolicy.COALESCE) builder.coalescer((left, right) -> (Integer) left + (Integer) right);
        events.register(builder.build());
        List<Integer> delivered = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        events.setDispatcher((eventId, payload) -> delivered.add((Integer) payload));
        events.setOverflowHandler(failed::add);
        EventKey<Integer> key = EventKey.of(id, Integer.class);
        events.emitSafe(key, 1);
        events.emitSafe(key, 2);
        events.emitSafe(key, 3);
        events.dispatchPending();
        assertEquals(expected, delivered);
        assertEquals(shouldFail ? List.of(id) : List.of(), failed);
        assertEquals(0, events.totalQueueDepth());
    }

    private void assertConcurrency(EventConcurrency concurrency, List<Integer> expected) {
        DefaultEventRegistry events = new DefaultEventRegistry();
        String id = "concurrency." + concurrency.name().toLowerCase();
        events.register(EventDescriptor.builder(id)
                .scriptName("Concurrency" + concurrency.name())
                .payloadType(VeloraTypes.INT)
                .defaultConcurrency(concurrency)
                .queueLimit(8)
                .build());
        List<Integer> delivered = new ArrayList<>();
        events.setDispatcher((eventId, payload) -> delivered.add((Integer) payload));
        EventKey<Integer> key = EventKey.of(id, Integer.class);
        events.emitSafe(key, 1);
        events.emitSafe(key, 2);
        events.emitSafe(key, 3);
        events.dispatchPending();
        assertEquals(expected, delivered);
        assertEquals(0, events.totalQueueDepth());
    }
}
