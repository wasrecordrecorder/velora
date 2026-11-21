package io.velora;

import io.velora.api.compiler.Diagnostic;
import io.velora.internal.foreign.*;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ForeignLangsTest {

    private static final class TestHost implements ForeignHost {
        final Map<String, String> files = new HashMap<>();
        final StringBuilder logs = new StringBuilder();

        @Override
        public String readFile(String scriptId, String relativePath) {
            return files.get(relativePath);
        }

        @Override
        public void log(String scriptId, int level, String message) {
            logs.append("[").append(level).append("] ").append(message).append("\n");
        }

        @Override
        public Object callApi(String scriptId, String namespace, String function, Object[] args) {
            if ("time".equals(namespace) && "now".equals(function)) return 123456789L;
            if ("bytes".equals(namespace) && "alloc".equals(function)) return new byte[args.length > 0 ? ((Number) args[0]).intValue() : 0];
            return null;
        }

        @Override
        public Object invokeNative(Object target, String method, Object[] args) {
            return "invoked:" + method;
        }

        @Override
        public Object getNativeField(Object target, String field) {
            return "field:" + field;
        }

        @Override
        public boolean setNativeField(Object target, String field, Object value) {
            return true;
        }

        @Override
        public long scheduleDelay(String scriptId, long durationNanos) {
            return 1;
        }

        @Override
        public void scheduleYield(String scriptId) {
        }
    }

    @Test
    public void nativeBridgeLoadsSuccessfully() {
        assertTrue(NativeBridge.isAvailable(), "NativeBridge should load velora_langs.dll: " + NativeBridge.loadError());
    }

    @Test
    public void jsSyntaxChecking() {
        List<Diagnostic> okDiags = NativeBridge.checkSyntaxJs("const x = 42;\nfunction test() { return x * 2; }\nexport default { name: 'Test' };", "main.js");
        assertTrue(okDiags.isEmpty(), "Valid JS should produce 0 errors");

        List<Diagnostic> errDiags = NativeBridge.checkSyntaxJs("const x = ;\nfunction bad()", "bad.js");
        assertFalse(errDiags.isEmpty(), "Invalid JS should produce errors");
        Diagnostic d = errDiags.get(0);
        assertTrue(d.isError());
        assertEquals("bad.js", d.range().filePath());
        assertEquals(1, d.range().startLine());
    }

    @Test
    public void luaSyntaxChecking() {
        List<Diagnostic> okDiags = NativeBridge.checkSyntaxLua("local x = 42\nfunction test()\n  return x * 2\nend\nreturn velora.define({ name = 'Test' })", "main.lua");
        assertTrue(okDiags.isEmpty(), "Valid Lua should produce 0 errors");

        List<Diagnostic> errDiags = NativeBridge.checkSyntaxLua("local x = \nfunction bad()", "bad.lua");
        assertFalse(errDiags.isEmpty(), "Invalid Lua should produce errors");
        Diagnostic d = errDiags.get(0);
        assertTrue(d.isError());
        assertEquals("bad.lua", d.range().filePath());
        assertEquals(2, d.range().startLine());
    }

    @Test
    public void jsExecutionWithExportDefaultAndHooks() {
        TestHost host = new TestHost();
        String jsCode = """
                let runCounter = 0;
                export default defineScript({
                    name: "JsModuleScript",
                    version: "2.1.0",
                    author: "Developer",
                    description: "Advanced JS Script",
                    settings: {
                        speed: setting.float("Speed", 3.5, { min: 1.0, max: 10.0, step: 0.5 }),
                        active: setting.boolean("Active", true)
                    },
                    onLoad() {
                        console.info("JS onLoad called");
                    },
                    onEnable() {
                        console.print("JS onEnable called");
                    },
                    events: {
                        Tick(e) {
                            runCounter++;
                            console.debug("Tick event fired: " + runCounter);
                        }
                    }
                });
                """;
        long ctx = NativeBridge.createJsContext("js_test", "main.js", jsCode, host);
        assertNotEquals(0, ctx);
        try {
            ForeignScriptDescriptor desc = NativeBridge.extractDescriptor(ctx);
            assertNotNull(desc);
            assertEquals("JsModuleScript", desc.name());
            assertEquals("2.1.0", desc.version());
            assertEquals("Developer", desc.author());
            assertEquals("Advanced JS Script", desc.description());
            assertEquals(2, desc.settings().size());
            assertEquals("Speed", desc.settings().get(0).name());
            assertEquals("Float", desc.settings().get(0).type());
            assertTrue(desc.lifecycleHooks().contains("ON_LOAD"));
            assertTrue(desc.lifecycleHooks().contains("ON_ENABLE"));
            assertTrue(desc.eventNames().contains("Tick"));

            ForeignExecutionResult loadRes = NativeBridge.invokeHook(ctx, "ON_LOAD");
            assertTrue(loadRes.isSuccess());

            ForeignExecutionResult enableRes = NativeBridge.invokeHook(ctx, "ON_ENABLE");
            assertTrue(enableRes.isSuccess());

            ForeignExecutionResult eventRes = NativeBridge.dispatchEvent(ctx, "Tick", null);
            assertTrue(eventRes.isSuccess());

            String logs = host.logs.toString();
            assertTrue(logs.contains("JS onLoad called"));
            assertTrue(logs.contains("JS onEnable called"));
            assertTrue(logs.contains("Tick event fired: 1"));
        } finally {
            NativeBridge.closeContext(ctx);
        }
    }

    @Test
    public void jsMultiFileModuleImport() {
        TestHost host = new TestHost();
        host.files.put("math_helper.js", "export function add(a, b) { return a + b; }\nexport const PI = 3.14159;");

        String mainJs = """
                import { add, PI } from "./math_helper.js";
                export default defineScript({
                    name: "MultiFileJs",
                    onLoad() {
                        const sum = add(10, 20);
                        console.print("Imported sum = " + sum + ", PI = " + PI);
                    }
                });
                """;
        long ctx = NativeBridge.createJsContext("js_multi", "main.js", mainJs, host);
        assertNotEquals(0, ctx);
        try {
            ForeignExecutionResult res = NativeBridge.invokeHook(ctx, "ON_LOAD");
            assertTrue(res.isSuccess());
            assertTrue(host.logs.toString().contains("Imported sum = 30, PI = 3.14159"));
        } finally {
            NativeBridge.closeContext(ctx);
        }
    }

    @Test
    public void jsComplexAlgorithmsTest() {
        TestHost host = new TestHost();
        String jsCode = """
                function fib(n) {
                    if (n <= 1) return n;
                    let a = 0, b = 1;
                    for (let i = 2; i <= n; i++) {
                        let c = a + b;
                        a = b;
                        b = c;
                    }
                    return b;
                }

                function aStarShortestPath() {
                    const grid = [
                        [0, 0, 0, 0],
                        [1, 1, 0, 1],
                        [0, 0, 0, 0],
                        [0, 1, 1, 0],
                        [0, 0, 0, 0]
                    ];
                    const queue = [{r: 0, c: 0, d: 0}];
                    const visited = new Set(['0,0']);
                    while (queue.length > 0) {
                        const {r, c, d} = queue.shift();
                        if (r === 4 && c === 3) return d;
                        const dirs = [[0,1], [0,-1], [1,0], [-1,0]];
                        for (const [dr, dc] of dirs) {
                            const nr = r + dr, nc = c + dc;
                            if (nr >= 0 && nr < 5 && nc >= 0 && nc < 4 && grid[nr][nc] === 0) {
                                const key = `${nr},${nc}`;
                                if (!visited.has(key)) {
                                    visited.add(key);
                                    queue.push({r: nr, c: nc, d: d + 1});
                                }
                            }
                        }
                    }
                    return -1;
                }

                export default defineScript({
                    name: "AlgorithmsJs",
                    onLoad() {
                        const fib20 = fib(20);
                        const dist = aStarShortestPath();
                        console.print("Fib(20) = " + fib20 + ", A* distance = " + dist);
                    }
                });
                """;
        long ctx = NativeBridge.createJsContext("js_algo", "main.js", jsCode, host);
        assertNotEquals(0, ctx);
        try {
            ForeignExecutionResult res = NativeBridge.invokeHook(ctx, "ON_LOAD");
            assertTrue(res.isSuccess());
            assertTrue(host.logs.toString().contains("Fib(20) = 6765, A* distance = 7"));
        } finally {
            NativeBridge.closeContext(ctx);
        }
    }

    @Test
    public void luaExecutionWithDefineAndHooks() {
        TestHost host = new TestHost();
        String luaCode = """
                local tickCount = 0
                return velora.define({
                    name = "LuaScriptTest",
                    version = "1.5.0",
                    author = "LuaAuthor",
                    description = "Full Lua 5.4 Script",
                    settings = {
                        range = velora.setting.float("Range", 4.2, { min = 1.0, max = 6.0, step = 0.1 }),
                        autoHeal = velora.setting.boolean("AutoHeal", true)
                    },
                    on_load = function()
                        console.info("Lua on_load executed")
                    end,
                    on_enable = function()
                        console.print("Lua on_enable executed")
                    end,
                    events = {
                        Tick = function(event)
                            tickCount = tickCount + 1
                            console.debug("Lua Tick count: " .. tickCount)
                        end
                    }
                })
                """;
        long ctx = NativeBridge.createLuaContext("lua_test", "main.lua", luaCode, host);
        assertNotEquals(0, ctx);
        try {
            ForeignScriptDescriptor desc = NativeBridge.extractDescriptor(ctx);
            assertNotNull(desc);
            assertEquals("LuaScriptTest", desc.name());
            assertEquals("1.5.0", desc.version());
            assertEquals("LuaAuthor", desc.author());
            assertEquals("Full Lua 5.4 Script", desc.description());
            assertEquals(2, desc.settings().size());
            ForeignSettingDef sRange = desc.settings().stream().filter(s -> s.name().equals("Range")).findFirst().orElse(null);
            ForeignSettingDef sEnabled = desc.settings().stream().filter(s -> s.name().equals("AutoHeal")).findFirst().orElse(null);
            assertNotNull(sRange);
            assertNotNull(sEnabled);
            assertEquals("Float", sRange.type());
            assertEquals("Boolean", sEnabled.type());
            assertTrue(desc.lifecycleHooks().contains("ON_LOAD"));
            assertTrue(desc.lifecycleHooks().contains("ON_ENABLE"));
            assertTrue(desc.eventNames().contains("Tick"));

            ForeignExecutionResult loadRes = NativeBridge.invokeHook(ctx, "ON_LOAD");
            assertTrue(loadRes.isSuccess());

            ForeignExecutionResult enableRes = NativeBridge.invokeHook(ctx, "ON_ENABLE");
            assertTrue(enableRes.isSuccess());

            ForeignExecutionResult eventRes = NativeBridge.dispatchEvent(ctx, "Tick", null);
            assertTrue(eventRes.isSuccess());

            String logs = host.logs.toString();
            assertTrue(logs.contains("Lua on_load executed"));
            assertTrue(logs.contains("Lua on_enable executed"));
            assertTrue(logs.contains("Lua Tick count: 1"));
        } finally {
            NativeBridge.closeContext(ctx);
        }
    }

    @Test
    public void luaMultiFileRequire() {
        TestHost host = new TestHost();
        host.files.put("helpers/calc.lua", "local M = {}\nfunction M.multiply(a, b) return a * b end\nM.CONST = 100\nreturn M");

        String mainLua = """
                local calc = require "helpers.calc"
                return velora.define({
                    name = "LuaMultiFile",
                    on_load = function()
                        local res = calc.multiply(7, 8)
                        console.print("Lua require result = " .. res .. ", CONST = " .. calc.CONST)
                    end
                })
                """;
        long ctx = NativeBridge.createLuaContext("lua_multi", "main.lua", mainLua, host);
        assertNotEquals(0, ctx);
        try {
            ForeignExecutionResult res = NativeBridge.invokeHook(ctx, "ON_LOAD");
            assertTrue(res.isSuccess());
            assertTrue(host.logs.toString().contains("Lua require result = 56, CONST = 100"));
        } finally {
            NativeBridge.closeContext(ctx);
        }
    }

    @Test
    public void luaComplexAlgorithmsAndBitwise54() {
        TestHost host = new TestHost();
        String luaCode = """
                -- Bitwise operators (Lua 5.3/5.4 feature)
                local function testBitwise()
                    local a = 0xF0
                    local b = 0xAA
                    local c = (a & b) | (~a & 0x0F)
                    return c
                end

                -- Metatables with operator overloading
                local Vector = {}
                Vector.__index = Vector
                function Vector.new(x, y)
                    return setmetatable({x = x, y = y}, Vector)
                end
                function Vector.__add(v1, v2)
                    return Vector.new(v1.x + v2.x, v1.y + v2.y)
                end

                return velora.define({
                    name = "LuaAlgorithms",
                    on_load = function()
                        local bitResult = testBitwise()
                        local v1 = Vector.new(10, 20)
                        local v2 = Vector.new(30, 40)
                        local v3 = v1 + v2
                        console.print("Bitwise: " .. bitResult .. ", Vec: " .. v3.x .. "," .. v3.y)
                    end
                })
                """;
        long ctx = NativeBridge.createLuaContext("lua_algo", "main.lua", luaCode, host);
        assertNotEquals(0, ctx);
        try {
            ForeignExecutionResult res = NativeBridge.invokeHook(ctx, "ON_LOAD");
            assertTrue(res.isSuccess());
            assertTrue(host.logs.toString().contains("Bitwise: 175, Vec: 40,60"));
        } finally {
            NativeBridge.closeContext(ctx);
        }
    }
}
