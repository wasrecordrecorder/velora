package io.velora;

import io.velora.api.Velora;
import io.velora.api.VeloraEngine;
import io.velora.api.VeloraLimits;
import io.velora.api.compiler.CompileRequest;
import io.velora.api.gpu.GpuApi;
import io.velora.host.AssetStore;
import io.velora.host.MainThreadExecutor;
import io.velora.host.VeloraClock;
import io.velora.host.VeloraFileSystem;
import io.velora.host.VeloraHost;
import io.velora.host.VeloraLogger;
import io.velora.host.WorkerExecutor;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.compiler.DefaultScriptCompiler;
import io.velora.internal.vm.ScriptValue;
import io.velora.internal.vm.VirtualMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class BinaryAssetsGpuV2Test {
    @TempDir
    Path temp;

    @Test
    void binaryAndAssetsApiExecuteWithConfinedPathsAndLegacyFallback() throws Exception {
        Path scripts = temp.resolve("scripts");
        Path assets = scripts.resolve("assets");
        Files.createDirectories(assets.resolve("data"));
        Files.writeString(assets.resolve("data/value.txt"), "42");
        Files.writeString(scripts.resolve("legacy.txt"), "old");
        TestHost host = new TestHost(scripts);
        VeloraEngine engine = Velora.builder().host(host).build();
        engine.freeze();
        CompiledModule module = compile(engine, "binary-assets", """
                @Script("BinaryAssets")
                script BinaryAssets {
                    int answer() {
                        data = bytes.base64("data:text/plain;base64,NDI=")
                        return string.parseInt(bytes.text(data)) + string.parseInt(assets.text("data/value.txt"))
                    }
                    String encoded() { return bytes.base64Encode(bytes.of([79, 75])) }
                    String legacy() { return assets.text("legacy.txt") }
                }
                """);
        assertEquals(84, ((Number) execute(engine, module, "answer").boxed()).intValue());
        assertEquals("T0s=", execute(engine, module, "encoded").boxed());
        assertEquals("old", execute(engine, module, "legacy").boxed());
        assertTrue(host.logs.stream().anyMatch(value -> value.contains("Legacy script asset path used")));
        assertThrows(IllegalArgumentException.class, () -> host.assets().bytes("../outside.txt"));
        assertEquals(List.of("data"), host.assets().list(""));
        engine.close();
    }

    @Test
    void typedGpuApiBuildsAndSubmitsValidatedDrawCalls() throws Exception {
        Path scripts = temp.resolve("scripts");
        TestHost host = new TestHost(scripts);
        RecordingBackend backend = new RecordingBackend();
        VeloraEngine engine = Velora.builder().host(host).build();
        GpuApi.register(engine, backend);
        engine.freeze();
        String source = """
                @Script("Gpu")
                script Gpu {
                    @Run run() {
                        format = gpu.format([gpu.floatAttribute("position", 2), gpu.colorAttribute("color")])
                        mesh = gpu.mesh(format)
                        gpu.putVec2(mesh, 0.0f, 0.0f)
                        gpu.putColor(mesh, -1)
                        gpu.putVec2(mesh, 10.0f, 0.0f)
                        gpu.putColor(mesh, -1)
                        gpu.putVec2(mesh, 0.0f, 10.0f)
                        gpu.putColor(mesh, -1)
                        shader = gpu.shader("void main(){}", "void main(){}")
                        pipeline = gpu.pipeline(shader, GpuDrawMode.TRIANGLES, GpuBlend.ALPHA, GpuDepth.OFF)
                        gpu.setFloat(pipeline, "lights[0].opacity", 1.0f)
                        type = gpu.renderType(pipeline)
                        gpu.draw(type, mesh)
                        gpu.draw(type, mesh)
                        gpu.releaseMesh(mesh)
                    }
                }
                """;
        CompiledModule module = compile(engine, "gpu", source);
        assertFalse(module.functions().isEmpty());
        Files.createDirectories(scripts.resolve("gpu"));
        Files.writeString(scripts.resolve("gpu/main.vls"), source);
        engine.scripts().discover();
        assertTrue(engine.scripts().enable("gpu").success());
        engine.tick();
        assertTrue(engine.debug().errors("gpu").isEmpty(), engine.debug().errors("gpu").toString());
        assertEquals(2, backend.calls.size());
        GpuApi.GpuDrawCall call = backend.calls.get(0);
        assertEquals(GpuApi.GpuSpace.HUD, call.space());
        assertEquals(GpuApi.GpuDrawMode.TRIANGLES, call.pipeline().drawMode());
        assertEquals(3, call.mesh().vertexCount());
        assertFalse(call.mesh().indexed());
        assertEquals(36, call.mesh().vertices().length);
        assertSame(call.mesh(), backend.calls.get(1).mesh());
        assertTrue(engine.scripts().disable("gpu").success());
        assertEquals(1, backend.resets);
        engine.close();
    }

    @Test
    void binaryHandlesParticipateInScriptMemoryAccounting() throws Exception {
        Path scripts = temp.resolve("scripts-memory");
        VeloraEngine engine = Velora.builder().host(new TestHost(scripts)).limits(VeloraLimits.builder().memoryPerScript(512).build()).build();
        engine.freeze();
        String source = """
                @Script("BinaryMemory")
                script BinaryMemory {
                    ByteArray? payload = null
                    @Run run() { payload = bytes.fromText("%s") }
                }
                """.formatted("x".repeat(1024));
        Files.createDirectories(scripts.resolve("binary-memory"));
        Files.writeString(scripts.resolve("binary-memory/main.vls"), source);
        engine.scripts().discover();
        assertTrue(engine.scripts().enable("binary-memory").success());
        engine.tick();
        assertFalse(engine.debug().errors("binary-memory").isEmpty());
        engine.close();
    }

    private CompiledModule compile(VeloraEngine engine, String id, String source) {
        CompileRequest request = CompileRequest.builder(id).source("main.vls", source).build();
        var result = engine.compiler().compile(request);
        assertTrue(result.success(), result.diagnostics().toString());
        return ((DefaultScriptCompiler) engine.compiler()).compileToModule(request);
    }

    private ScriptValue execute(VeloraEngine engine, CompiledModule module, String function) {
        var result = new VirtualMachine(engine.api(), module.settings(), 100_000).execute(module, module.functionByName(function).index(), new ScriptValue[0]);
        assertTrue(result.success(), result.error() == null ? "VM failed" : result.error().message());
        return result.returnValue();
    }

    private static final class RecordingBackend implements GpuApi.GpuBackend {
        private final List<GpuApi.GpuDrawCall> calls = new ArrayList<>();
        private int resets;

        @Override
        public void submit(String scriptId, GpuApi.GpuRenderType renderType, GpuApi.GpuMesh mesh) {
            calls.add(new GpuApi.GpuDrawCall(renderType, mesh));
        }

        @Override
        public void reset(String scriptId) {
            resets++;
        }
    }

    private static final class TestHost implements VeloraHost {
        private final List<String> logs = new ArrayList<>();
        private final VeloraFileSystem fileSystem;
        private final AssetStore assets;

        private TestHost(Path scripts) {
            this.fileSystem = VeloraFileSystem.local(scripts);
            this.assets = AssetStore.local(scripts.resolve("assets"), scripts, 1_048_576, logger());
        }

        @Override
        public String id() {
            return "test";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public MainThreadExecutor mainThread() {
            return new MainThreadExecutor() {
                @Override
                public boolean isMainThread() {
                    return true;
                }

                @Override
                public void execute(Runnable action) {
                    action.run();
                }
            };
        }

        @Override
        public WorkerExecutor workers() {
            return new WorkerExecutor() {
                @Override
                public void execute(Runnable action) {
                    action.run();
                }

                @Override
                public void shutdown() {
                }
            };
        }

        @Override
        public VeloraClock clock() {
            return new VeloraClock() {
                @Override
                public long nanoTime() {
                    return System.nanoTime();
                }

                @Override
                public long currentTimeMillis() {
                    return System.currentTimeMillis();
                }
            };
        }

        @Override
        public VeloraLogger logger() {
            return new VeloraLogger() {
                @Override
                public void debug(String message) {
                    logs.add(message);
                }

                @Override
                public void info(String message) {
                    logs.add(message);
                }

                @Override
                public void warn(String message) {
                    logs.add(message);
                }

                @Override
                public void error(String message, Throwable error) {
                    logs.add(message);
                }
            };
        }

        @Override
        public VeloraFileSystem fileSystem() {
            return fileSystem;
        }

        @Override
        public AssetStore assets() {
            return assets;
        }
    }
}
