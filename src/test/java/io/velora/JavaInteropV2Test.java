package io.velora;

import io.velora.api.Velora;
import io.velora.api.VeloraEngine;
import io.velora.api.compiler.CompileRequest;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.language.EditorSession;
import io.velora.binding.BindingValidationException;
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

        @VeloraFunction(name = "maybe", returnType = "String?")
        public static String maybe() {
            return null;
        }

        @VeloraFunction(name = "isNull")
        public static boolean isNull(@VeloraParam(value = "value", type = "String?") String value) {
            return value == null;
        }
    }

    static final class PlainUtil {
        public static int value() { return 1; }
    }


    @VeloraImport("client.$Bad")
    static final class InvalidImportName {
        @VeloraFunction(name = "value")
        public static int value() { return 1; }
    }

    @VeloraImport("client.util.RemappedUtil")
    static final class RemappedUtil {
        @VeloraFunction(name = "value")
        public static int value() {
            return 7;
        }
    }

    @Test
    void explicitJavaImportWithoutExportAnnotationFailsFast() {
        VeloraEngine engine = Velora.builder().host(host()).build();
        assertThrows(BindingValidationException.class, () -> engine.javaImports().register(PlainUtil.class));
        assertThrows(IllegalArgumentException.class, () -> engine.javaImports().register((Class<?>) null));
        assertThrows(BindingValidationException.class, () -> engine.javaImports().register(InvalidImportName.class));
        assertTrue(engine.javaImports().all().isEmpty());
        engine.close();
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
    void annotatedJavaImportsSupportNullableTypeOverrides() {
        VeloraEngine engine = Velora.builder().host(host()).build();
        engine.javaImports().register(MathUtil.class);
        engine.freeze();
        CompileRequest request = CompileRequest.builder("nullable").source("main.vls", "import client.util.MathUtil\n@Script(\"Nullable\")\nscript Nullable { boolean answer() { String? value = MathUtil.maybe() return MathUtil.isNull(value) } }").build();
        var result = engine.compiler().compile(request);
        assertTrue(result.success(), result.diagnostics().toString());
        var module = ((DefaultScriptCompiler) engine.compiler()).compileToModule(request);
        var execution = new VirtualMachine(engine.api(), List.of(), 100_000).execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
        assertTrue(execution.success(), String.valueOf(execution.error()));
        assertEquals(true, execution.returnValue().boxed());
        engine.close();
    }

    @Test
    void importDescriptorKeepsRuntimeClassReference() {
        VeloraEngine engine = Velora.builder().host(host()).build();
        engine.javaImports().register(RemappedUtil.class);
        var imported = engine.javaImports().find("client.util.RemappedUtil");
        assertNotNull(imported);
        assertSame(RemappedUtil.class, imported.type());
        assertNull(imported.source());
    }

    @Test
    void compiledClassFileCanBeRegistered() throws Exception {
        VeloraEngine engine = Velora.builder().host(host()).build();
        Path file = Path.of(RemappedUtil.class.getResource("JavaInteropV2Test$RemappedUtil.class").toURI());
        engine.javaImports().register(file, RemappedUtil.class.getClassLoader());
        var imported = engine.javaImports().find("client.util.RemappedUtil");
        assertNotNull(imported);
        assertSame(RemappedUtil.class, imported.type());
        assertEquals(file.toAbsolutePath().normalize(), imported.source());
    }

    @Test
    void compiledDirectoryLoadsExportsWithoutLinkingUnrelatedClasses() throws Exception {
        Path root = java.nio.file.Files.createTempDirectory("velora-java-import-");
        Path source = root.resolve("source/ext");
        Path classes = root.resolve("classes");
        java.nio.file.Files.createDirectories(source);
        java.nio.file.Files.writeString(source.resolve("Missing.java"), "package ext; public class Missing {}");
        java.nio.file.Files.writeString(source.resolve("Unrelated.java"), "package ext; public class Unrelated extends Missing {}");
        java.nio.file.Files.writeString(source.resolve("External.java"), "package ext; import io.velora.binding.annotation.*; @VeloraImport(\"client.util.External\") public class External { @VeloraFunction(name=\"value\") public static int value() { return 73; } }");
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int exit = compiler.run(null, null, null, "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(),
                source.resolve("Missing.java").toString(), source.resolve("Unrelated.java").toString(), source.resolve("External.java").toString());
        assertEquals(0, exit);
        java.nio.file.Files.delete(classes.resolve("ext/Missing.class"));

        VeloraEngine engine = Velora.builder().host(host()).build();
        engine.javaImports().register(classes);
        var imported = engine.javaImports().find("client.util.External");
        assertNotNull(imported);
        assertEquals("ext.External", imported.type().getName());
        engine.freeze();
        CompileRequest request = CompileRequest.builder("external").source("main.vls", "import client.util.External\n@Script(\"ExternalScript\")\n@Version(\"1\")\nscript ExternalScript { int answer() { return External.value() } }").build();
        var result = engine.compiler().compile(request);
        assertTrue(result.success(), result.diagnostics().toString());
        var module = ((DefaultScriptCompiler) engine.compiler()).compileToModule(request);
        var execution = new VirtualMachine(engine.api(), List.of(), 100_000).execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
        assertTrue(execution.success(), String.valueOf(execution.error()));
        assertEquals(73, ((Number) execution.returnValue().boxed()).intValue());
        engine.close();
    }

    @Test
    void malformedClassFileIsRejectedAsBindingValidationError() throws Exception {
        Path file = java.nio.file.Files.createTempFile("velora-malformed-", ".class");
        try (var out = new java.io.DataOutputStream(java.nio.file.Files.newOutputStream(file))) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(65);
            out.writeShort(1);
            out.writeShort(0);
            out.writeShort(65535);
            out.writeShort(0);
        }
        VeloraEngine engine = Velora.builder().host(host()).build();
        assertThrows(BindingValidationException.class, () -> engine.javaImports().register(file));
        assertTrue(engine.javaImports().all().isEmpty());
        engine.close();
    }

    @Test
    void editorToolingResolvesFormattedImportsWithTrailingComments() {
        VeloraEngine engine = Velora.builder().host(host()).build();
        engine.javaImports().register(MathUtil.class);
        engine.freeze();
        String source = "import client.util.MathUtil; // utility\n@Script(\"Tools\")\nscript Tools {\n    Int answer() { return MathUtil.s }\n}";
        try (EditorSession editor = engine.language().openEditor("tools", "main.vls")) {
            editor.updateText(source);
            String line = "    Int answer() { return MathUtil.s }";
            assertTrue(editor.completions(4, line.indexOf("MathUtil.s") + "MathUtil.s".length() + 1).stream().anyMatch(item -> item.label().equals("sum")));
        }
        engine.close();
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
