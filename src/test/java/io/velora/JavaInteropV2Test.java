package io.velora;

import io.velora.api.Velora;
import io.velora.api.VeloraEngine;
import io.velora.api.compiler.CompileRequest;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.language.EditorSession;
import io.velora.binding.annotation.VeloraFunction;
import io.velora.binding.annotation.VeloraImport;
import io.velora.binding.annotation.VeloraParam;
import io.velora.binding.annotation.VeloraProperty;
import io.velora.host.MainThreadExecutor;
import io.velora.host.VeloraClock;
import io.velora.host.VeloraFileSystem;
import io.velora.host.VeloraHost;
import io.velora.host.VeloraLogger;
import io.velora.host.WorkerExecutor;
import io.velora.internal.compiler.DefaultScriptCompiler;
import io.velora.internal.vm.PrimitiveValue;
import io.velora.internal.vm.ScriptValue;
import io.velora.internal.vm.VirtualMachine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaInteropV2Test {
    @VeloraImport("client.util.MathUtil")
    static final class MathUtil {
        @VeloraFunction(name = "sum")
        public static int sum(@VeloraParam("a") int a, @VeloraParam("b") int b) {
            return a + b;
        }

        @VeloraProperty(name = "answer")
        public static int answer() {
            return 42;
        }
    }

    @VeloraImport("client.util.RemappedUtil")
    static final class RemappedUtil {
        @VeloraFunction(name = "value")
        public static int value() {
            return 7;
        }
    }

    @Test
    void importedStaticUtilityCompilesAndExecutes() {
        VeloraEngine engine = Velora.builder().host(host()).build();
        engine.javaImports().register(MathUtil.class);
        engine.freeze();
        String source = """
                import client.util.MathUtil
                @Script("Interop")
                @Version("1")
                script Interop {
                    Int answer() {
                        return MathUtil.sum(40, 2)
                    }
                }
                """;
        CompileRequest request = CompileRequest.builder("interop").source("main.vls", source).build();
        var result = engine.compiler().compile(request);
        assertTrue(result.success(), result.diagnostics().toString());
        var module = ((DefaultScriptCompiler) engine.compiler()).compileToModule(request);
        var execution = new VirtualMachine(engine.api(), List.of(), 100_000).execute(module, 0, new ScriptValue[0]);
        assertTrue(execution.success(), execution.error() != null ? execution.error().message() : "VM failed");
        assertEquals(42, ((PrimitiveValue.IntV) execution.returnValue()).value());
    }

    @Test
    void sourcePathUsesRuntimeClassResolver() throws Exception {
        VeloraEngine engine = Velora.builder().host(host()).build();
        Path source = Files.createTempFile("velora-remap", ".java");
        try {
            Files.writeString(source, "package dev.client;\n@VeloraImport(\"client.util.RemappedUtil\")\npublic final class SourceUtil {}\n");
            engine.javaImports().register(source, name -> {
                assertEquals("dev.client.SourceUtil", name);
                return RemappedUtil.class.getName();
            });
            var imported = engine.javaImports().find("client.util.RemappedUtil");
            assertNotNull(imported);
            assertEquals(RemappedUtil.class.getName(), imported.runtimeClassName());
            assertEquals(source.toAbsolutePath().normalize(), imported.source());
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void sourceDirectoryScansJavaImports() throws Exception {
        VeloraEngine engine = Velora.builder().host(host()).build();
        Path directory = Files.createTempDirectory("velora-imports");
        Path source = directory.resolve("dev/client/SourceUtil.java");
        try {
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package dev.client;\n@VeloraImport(\"client.util.RemappedUtil\")\npublic final class SourceUtil {}\n");
            engine.javaImports().register(directory, name -> RemappedUtil.class.getName());
            assertNotNull(engine.javaImports().find("client.util.RemappedUtil"));
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(source.getParent());
            Files.deleteIfExists(source.getParent().getParent());
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void editorReportsUnknownJavaImport() {
        VeloraEngine engine = Velora.builder().host(host()).build();
        engine.freeze();
        try (EditorSession editor = engine.language().openEditor("bad", "main.vls")) {
            editor.updateText("import missing.Util\n@Script(\"Bad\")\nscript Bad { @Run run() {} }");
            assertTrue(editor.snapshot().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == DiagnosticCode.SEMANTIC_UNKNOWN_IMPORT));
        }
    }

    private VeloraHost host() {
        return new VeloraHost() {
            public String id() { return "test"; }
            public String version() { return "1"; }
            public MainThreadExecutor mainThread() { return new MainThreadExecutor() {
                public boolean isMainThread() { return true; }
                public void execute(Runnable action) { action.run(); }
            }; }
            public WorkerExecutor workers() { return new WorkerExecutor() {
                public void execute(Runnable action) { action.run(); }
                public void shutdown() { }
            }; }
            public VeloraClock clock() { return new VeloraClock() {
                public long nanoTime() { return System.nanoTime(); }
                public long currentTimeMillis() { return System.currentTimeMillis(); }
            }; }
            public VeloraLogger logger() { return new VeloraLogger() {
                public void debug(String message) { }
                public void info(String message) { }
                public void warn(String message) { }
                public void error(String message, Throwable error) { }
            }; }
            public VeloraFileSystem fileSystem() { return null; }
        };
    }
}
