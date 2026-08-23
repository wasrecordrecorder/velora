package io.velora;

import io.velora.api.Velora;
import io.velora.api.event.EventDescriptor;
import io.velora.api.event.EventKey;
import io.velora.api.script.ScriptCreateRequest;
import io.velora.host.MainThreadExecutor;
import io.velora.host.VeloraClock;
import io.velora.host.VeloraFileSystem;
import io.velora.host.VeloraHost;
import io.velora.host.VeloraLogger;
import io.velora.host.WorkerExecutor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmediateEventV2Test {
    @Test
    void immediateEventMutatesLiveHandleBeforeEmitterReturnsAndKeepsScriptState() throws Exception {
        var root = Files.createTempDirectory("velora-immediate-");
        var engine = Velora.builder().host(host(root)).build();
        var mutableType = engine.types().handle("MutableProbe", MutableProbe.class);
        engine.api().namespace("probe", namespace -> namespace.function("set", io.velora.api.type.VeloraTypes.UNIT,
                parameters -> parameters.required("probe", mutableType).required("value", io.velora.api.type.VeloraTypes.INT), context -> {
                    context.argument("probe", MutableProbe.class).value = context.argument("value", Integer.class);
                    return null;
                }));
        engine.events().register(EventDescriptor.builder("test.immediate").scriptName("Immediate").payloadType(mutableType).build());
        engine.freeze();
        String source = "@Script(\"Immediate\")\nscript Immediate { Int calls = 0\n @Immediate on(MutableProbe probe) { calls += 1\n probe.set(probe, 40 + calls) } }";
        assertTrue(engine.scripts().create(ScriptCreateRequest.builder("immediate", "Immediate").file("main.vls", source).build()).success());
        assertTrue(engine.scripts().enable("immediate").success());
        EventKey<MutableProbe> key = EventKey.of("test.immediate", MutableProbe.class);
        MutableProbe first = new MutableProbe();
        MutableProbe second = new MutableProbe();
        engine.events().emitImmediate(key, first);
        engine.events().emitImmediate(key, second);
        assertEquals(41, first.value);
        assertEquals(42, second.value);
        engine.close();
    }

    private VeloraHost host(java.nio.file.Path root) {
        return new VeloraHost() {
            public String id() { return "immediate-test"; }
            public String version() { return "1"; }
            public MainThreadExecutor mainThread() { return new MainThreadExecutor() {
                public boolean isMainThread() { return true; }
                public void execute(Runnable action) { action.run(); }
            }; }
            public WorkerExecutor workers() { return new WorkerExecutor() {
                public void execute(Runnable action) { action.run(); }
                public void shutdown() {}
            }; }
            public VeloraClock clock() { return new VeloraClock() {
                public long nanoTime() { return System.nanoTime(); }
                public long currentTimeMillis() { return System.currentTimeMillis(); }
            }; }
            public VeloraLogger logger() { return new VeloraLogger() {
                public void debug(String message) {}
                public void info(String message) {}
                public void warn(String message) {}
                public void error(String message, Throwable error) {}
            }; }
            public VeloraFileSystem fileSystem() { return VeloraFileSystem.local(root); }
        };
    }

    static final class MutableProbe {
        int value;
    }
}
