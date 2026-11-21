package io.velora;

import io.velora.api.*;
import io.velora.api.compiler.*;
import io.velora.api.event.*;
import io.velora.api.function.*;
import io.velora.api.language.EditorSession;
import io.velora.api.script.*;
import io.velora.api.type.VeloraTypes;
import io.velora.host.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ForeignEngineIntegrationTest {

    private record TestHost(LocalVeloraFileSystem fs, List<String> logs) implements VeloraHost {
        @Override public String id() { return "test-host"; }
        @Override public String version() { return "2.0.0"; }
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
            @Override public void debug(String m) { logs.add("[DEBUG] " + m); }
            @Override public void info(String m) { logs.add("[INFO] " + m); }
            @Override public void warn(String m) { logs.add("[WARN] " + m); }
            @Override public void error(String m, Throwable t) { logs.add("[ERROR] " + m); }
        }; }
        @Override public VeloraFileSystem fileSystem() { return fs; }
    }

    @Test
    void testJsScriptFullLifecycleAndEvents(@TempDir Path tempDir) {
        List<String> logs = new ArrayList<>();
        LocalVeloraFileSystem fs = new LocalVeloraFileSystem(tempDir);
        TestHost host = new TestHost(fs, logs);

        VeloraEngine engine = Velora.builder().host(host).build();
        EventKey<String> tickKey = EventKey.of("Tick", String.class);
        engine.events().register(EventDescriptor.builder("Tick")
                .scriptName("Tick")
                .payloadType(VeloraTypes.STRING)
                .build());
        engine.freeze();

        String jsSource = """
                let ticks = 0;
                export default defineScript({
                    name: "TestJsLifecycle",
                    version: "1.0.0",
                    author: "Developer",
                    description: "End-to-End JS Test",
                    settings: {
                        speed: setting.float("Speed", 2.5, { min: 1.0, max: 10.0, step: 0.5 }),
                        active: setting.boolean("Active", true)
                    },
                    onLoad() {
                        console.info("JS LOADED");
                    },
                    onEnable() {
                        console.info("JS ENABLED");
                    },
                    onDisable() {
                        console.info("JS DISABLED");
                    },
                    events: {
                        Tick(event) {
                            ticks++;
                            console.info("JS TICK: " + ticks + " payload=" + event);
                        }
                    }
                });
                """;

        ScriptCreateRequest createReq = ScriptCreateRequest.builder("js_test", "TestJsLifecycle")
                .file("main.js", jsSource)
                .build();
        ScriptOperationResult createRes = engine.scripts().create(createReq);
        assertTrue(createRes.success(), createRes.message());

        ScriptOperationResult enableRes = engine.scripts().enable("js_test");
        assertTrue(enableRes.success(), enableRes.message());

        engine.events().emitImmediate(tickKey, "tick-payload-1");
        engine.events().emitImmediate(tickKey, "tick-payload-2");

        ScriptOperationResult disableRes = engine.scripts().disable("js_test");
        assertTrue(disableRes.success(), disableRes.message());

        assertTrue(logs.stream().anyMatch(l -> l.contains("JS LOADED")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("JS ENABLED")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("JS TICK: 1 payload=tick-payload-1")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("JS TICK: 2 payload=tick-payload-2")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("JS DISABLED")));

        EditorSession session = engine.language().openEditor("js_test", "main.js");
        var snap = session.snapshot();
        assertNotNull(snap);
        assertTrue(snap.diagnostics().isEmpty());
        assertFalse(snap.tokens().isEmpty());
    }

    @Test
    void testLuaScriptFullLifecycleAndEvents(@TempDir Path tempDir) {
        List<String> logs = new ArrayList<>();
        LocalVeloraFileSystem fs = new LocalVeloraFileSystem(tempDir);
        TestHost host = new TestHost(fs, logs);

        VeloraEngine engine = Velora.builder().host(host).build();
        EventKey<String> tickKey = EventKey.of("Tick", String.class);
        engine.events().register(EventDescriptor.builder("Tick")
                .scriptName("Tick")
                .payloadType(VeloraTypes.STRING)
                .build());
        engine.freeze();

        String luaSource = """
                local ticks = 0
                return velora.define({
                    name = "TestLuaLifecycle",
                    version = "1.0.0",
                    author = "Developer",
                    description = "End-to-End Lua Test",
                    settings = {
                        speed = velora.setting.float("Speed", 3.5, { min = 1.0, max = 10.0, step = 0.5 }),
                        active = velora.setting.boolean("Active", true)
                    },
                    on_load = function()
                        console.info("LUA LOADED")
                    end,
                    on_enable = function()
                        console.info("LUA ENABLED")
                    end,
                    on_disable = function()
                        console.info("LUA DISABLED")
                    end,
                    events = {
                        Tick = function(event)
                            ticks = ticks + 1
                            console.info("LUA TICK: " .. ticks .. " payload=" .. tostring(event))
                        end
                    }
                })
                """;

        ScriptCreateRequest createReq = ScriptCreateRequest.builder("lua_test", "TestLuaLifecycle")
                .file("main.lua", luaSource)
                .build();
        ScriptOperationResult createRes = engine.scripts().create(createReq);
        assertTrue(createRes.success(), createRes.message());

        ScriptOperationResult enableRes = engine.scripts().enable("lua_test");
        assertTrue(enableRes.success(), enableRes.message());

        engine.events().emitImmediate(tickKey, "tick-payload-lua");

        ScriptOperationResult disableRes = engine.scripts().disable("lua_test");
        assertTrue(disableRes.success(), disableRes.message());

        assertTrue(logs.stream().anyMatch(l -> l.contains("LUA LOADED")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("LUA ENABLED")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("LUA TICK: 1 payload=tick-payload-lua")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("LUA DISABLED")));

        EditorSession session = engine.language().openEditor("lua_test", "main.lua");
        var snap = session.snapshot();
        assertNotNull(snap);
        assertTrue(snap.diagnostics().isEmpty());
        assertFalse(snap.tokens().isEmpty());
    }
}
