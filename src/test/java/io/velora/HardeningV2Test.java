package io.velora;

import io.velora.api.Velora;
import io.velora.api.VeloraEngine;
import io.velora.api.VeloraLimits;
import io.velora.api.compiler.*;
import io.velora.api.permission.PermissionSet;
import io.velora.api.type.VeloraTypes;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingValue;
import io.velora.internal.setting.SettingStore;
import io.velora.binding.BindingValidationException;
import io.velora.binding.annotation.VeloraFunction;
import io.velora.binding.annotation.VeloraNamespace;
import io.velora.binding.annotation.VeloraProperty;
import io.velora.host.*;
import io.velora.internal.bytecode.*;
import io.velora.internal.compiler.DefaultScriptCompiler;
import io.velora.internal.debug.RuntimeErrorStore;
import io.velora.internal.ir.IrBuilder;
import io.velora.internal.ir.IrModule;
import io.velora.internal.ir.IrVerifier;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.LexerResult;
import io.velora.internal.language.DefaultEditorSession;
import io.velora.internal.parser.ParseResult;
import io.velora.internal.parser.Parser;
import io.velora.internal.registry.*;
import io.velora.internal.scheduler.ScriptFiber;
import io.velora.internal.scheduler.ScriptScheduler;
import io.velora.internal.semantic.ResolvedScript;
import io.velora.internal.semantic.SemanticAnalyzer;
import io.velora.internal.setting.DefaultSettingRegistry;
import io.velora.internal.vm.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HardeningV2Test {
    private DefaultTypeRegistry types;
    private DefaultSettingRegistry settings;
    private DefaultPermissionRegistry permissions;
    private DefaultConstantRegistry constants;
    private DefaultApiRegistry api;

    @BeforeEach
    void setUp() {
        types = new DefaultTypeRegistry();
        settings = new DefaultSettingRegistry();
        permissions = new DefaultPermissionRegistry();
        constants = new DefaultConstantRegistry();
        api = new DefaultApiRegistry(types);
    }

    private CompiledModule compile(String source) {
        LexerResult lex = new Lexer(source, "main.vls").lex();
        assertTrue(lex.diagnostics().isEmpty(), "Lexer errors: " + lex.diagnostics());
        ParseResult parse = Parser.parse(source, "main.vls");
        assertTrue(parse.diagnostics().isEmpty(), "Parser errors: " + parse.diagnostics());
        SemanticAnalyzer analyzer = new SemanticAnalyzer(types, settings, api, constants, permissions);
        ResolvedScript resolved = analyzer.analyze(parse.scriptNode());
        assertTrue(analyzer.diagnostics().isEmpty(), "Semantic errors: " + analyzer.diagnostics());
        IrModule ir = new IrBuilder(resolved, api).build();
        assertTrue(new IrVerifier().verify(ir).isEmpty());
        CompiledModule module = new BytecodeWriter().write(ir);
        assertTrue(new BytecodeVerifier().verify(module).stream().noneMatch(Diagnostic::isError));
        return module;
    }

    private List<Diagnostic> semanticDiagnostics(String source) {
        ParseResult parse = Parser.parse(source, "main.vls");
        assertNotNull(parse.scriptNode());
        SemanticAnalyzer analyzer = new SemanticAnalyzer(types, settings, api, constants, permissions);
        analyzer.analyze(parse.scriptNode());
        return analyzer.diagnostics();
    }

    private VmExecutionResult execute(CompiledModule module) {
        return new VirtualMachine(api, List.of(), 500_000).execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
    }

    private DefaultScriptCompiler compiler() {
        return new DefaultScriptCompiler(types, settings, api, constants, permissions);
    }

    @Test
    void floatArithmeticAndUnaryMinus() {
        CompiledModule module = compile("@Script(name=\"T\", version=\"1\")\nscript T { float answer() { return -(10.0f - 2.0f * 3.0f / 2.0f) } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(-7.0, ((Number) result.returnValue().boxed()).doubleValue());
    }

    @Test
    void mixedNumericEqualityUsesNumericValue() {
        CompiledModule module = compile("@Script(name=\"T\", version=\"1\")\nscript T { boolean answer() { return 1 == 1L } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(true, result.returnValue().boxed());
    }

    @Test
    void stringConcatenationHonorsRuntimeLimit() {
        CompiledModule module = compile("@Script(name=\"T\", version=\"1\")\nscript T { String answer() { return \"abc\" + \"def\" } }");
        VmExecutionResult result = new VirtualMachine(api, List.of(), null, 100_000, 128, 5, 100, 8)
                .execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
        assertFalse(result.success());
        assertEquals(DiagnosticCode.RUNTIME_RESOURCE_LIMIT, result.error().code());
    }

    @Test
    void invalidFunctionIndexReturnsVmFailure() {
        CompiledModule module = compile("@Script(name=\"T\", version=\"1\")\nscript T { int answer() { return 42 } }");
        VmExecutionResult result = new VirtualMachine(api, List.of(), 100_000).execute(module, 999, new ScriptValue[0]);
        assertFalse(result.success());
        assertTrue(result.error().message().contains("Function not found"));
    }

    @Test
    void semanticRejectsInvalidOperatorsAndAssignments() {
        List<Diagnostic> operator = semanticDiagnostics("@Script(name=\"T\", version=\"1\")\nscript T { int answer() { return true + false } }");
        assertTrue(operator.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
        List<Diagnostic> assignment = semanticDiagnostics("@Script(name=\"T\", version=\"1\")\nscript T { int answer() { int x = 1\n x = \"bad\"\n return x } }");
        assertTrue(assignment.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
    }

    @Test
    void semanticRejectsRuntimeFieldInitializer() {
        List<Diagnostic> diagnostics = semanticDiagnostics("@Script(name=\"T\", version=\"1\")\nscript T { int make() { return 1 }\n int value = make()\n int answer() { return value } }");
        assertTrue(diagnostics.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_NON_CONSTANT_FIELD_INIT));
    }

    @Test
    void constantExpressionFieldInitializerExecutes() {
        CompiledModule module = compile("@Script(name=\"T\", version=\"1\")\nscript T { int value = 20 + 22\n int answer() { return value } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(42, ((Number) result.returnValue().boxed()).intValue());
    }

    @Test
    void bytecodeVerifierRejectsUnderflowBadJumpAndFallthrough() {
        CompiledModule base = compile("@Script(name=\"T\", version=\"1\")\nscript T { int answer() { return 1 } }");
        CompiledFunction underflow = new CompiledFunction("answer", 0, 0, 0, 1, false, false,
                new int[]{Opcode.POP.ordinal(), Opcode.RETURN.ordinal()}, new int[0]);
        CompiledFunction badJump = new CompiledFunction("answer", 0, 0, 0, 1, false, false,
                new int[]{Opcode.JUMP.ordinal(), 1}, new int[0]);
        CompiledFunction fallthrough = new CompiledFunction("answer", 0, 0, 0, 1, false, false,
                new int[]{Opcode.TRUE.ordinal()}, new int[0]);
        assertTrue(new BytecodeVerifier().verify(withFunction(base, underflow)).stream().anyMatch(d -> d.code() == DiagnosticCode.BYTECODE_STACK_MISMATCH));
        assertTrue(new BytecodeVerifier().verify(withFunction(base, badJump)).stream().anyMatch(d -> d.code() == DiagnosticCode.BYTECODE_BAD_JUMP));
        assertTrue(new BytecodeVerifier().verify(withFunction(base, fallthrough)).stream().anyMatch(d -> d.code() == DiagnosticCode.BYTECODE_BAD_OPERAND));
    }

    @Test
    void bytecodeVerifierRejectsBranchStackMismatchAndSmallMaxStack() {
        CompiledModule base = compile("@Script(name=\"T\", version=\"1\")\nscript T { int answer() { return 1 } }");
        int constant = 0;
        CompiledFunction mismatch = new CompiledFunction("answer", 0, 0, 0, 1, false, false,
                new int[]{Opcode.TRUE.ordinal(), Opcode.JUMP_IF_FALSE.ordinal(), 5, Opcode.CONST.ordinal(), constant, Opcode.RETURN.ordinal()}, new int[0]);
        CompiledFunction stack = new CompiledFunction("answer", 0, 0, 0, 0, false, false,
                new int[]{Opcode.TRUE.ordinal(), Opcode.RETURN.ordinal()}, new int[0]);
        assertTrue(new BytecodeVerifier().verify(withFunction(base, mismatch)).stream().anyMatch(d -> d.code() == DiagnosticCode.BYTECODE_STACK_MISMATCH));
        assertTrue(new BytecodeVerifier().verify(withFunction(base, stack)).stream().anyMatch(d -> d.code() == DiagnosticCode.BYTECODE_STACK_MISMATCH));
    }

    @Test
    void compilerFoldsConstantArithmeticBeforeBytecode() {
        CompiledModule module = compiler().compileToModule(CompileRequest.builder("FoldT")
                .source("main.vls", "@Script(name=\"FoldT\", version=\"1\")\nscript FoldT { int answer() { return 20 + 22 } }").build());
        assertNotNull(module);
        int[] code = module.functionByName("answer").code();
        assertEquals(3, code.length);
        VmExecutionResult result = new VirtualMachine(api, List.of(), 100_000).execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
        assertTrue(result.success());
        assertEquals(42, ((Number) result.returnValue().boxed()).intValue());
    }

    @Test
    void compilerOptimizationPreservesJumpTargets() {
        CompiledModule module = compiler().compileToModule(CompileRequest.builder("JumpFoldT")
                .source("main.vls", "@Script(name=\"JumpFoldT\", version=\"1\")\nscript JumpFoldT { int answer() { int x = 20 + 22\n if (x == 42) { return 1 + 1 } return 0 } }").build());
        assertNotNull(module);
        VmExecutionResult result = new VirtualMachine(api, List.of(), 100_000).execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
        assertTrue(result.success());
        assertEquals(2, ((Number) result.returnValue().boxed()).intValue());
    }

    @Test
    void compilerCacheOnlyUsesContentAndRegistryHash() {
        DefaultScriptCompiler compiler = compiler();
        String source = "@Script(name=\"CacheT\", version=\"1\")\nscript CacheT { int answer() { return 42 } }";
        CompileResult full = compiler.compile(CompileRequest.builder("CacheT").source("main.vls", source).mode(CompileMode.FULL).build());
        CompileResult hit = compiler.compile(CompileRequest.builder("CacheT").source("main.vls", source).mode(CompileMode.CACHE_ONLY).build());
        CompileResult miss = compiler.compile(CompileRequest.builder("CacheT").source("main.vls", source.replace("42", "43")).mode(CompileMode.CACHE_ONLY).build());
        assertTrue(full.success());
        assertTrue(hit.success());
        assertFalse(miss.success());
        assertTrue(miss.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.COMPILER_CACHE_MISS));
    }

    @Test
    void compilerSourceDetectionIgnoresScriptTextInsideString() {
        DefaultScriptCompiler compiler = compiler();
        String main = "@Script(name=\"Multi\", version=\"1\")\nscript Multi { int answer() { return helper() } }";
        String helper = "int helper() { String text = \"script Fake {}\"\n return 42 }";
        CompileResult result = compiler.compile(CompileRequest.builder("Multi").source("main.vls", main).source("helper.vls", helper).build());
        assertTrue(result.success(), "Compiler diagnostics: " + result.diagnostics());
    }

    @Test
    void bytecodeRoundTripKeepsMetadataAndFieldInitializers() {
        DefaultScriptCompiler compiler = compiler();
        String source = "@Script(name=\"RoundTrip\", version=\"2\", author=\"Ava\", description=\"test\")\nscript RoundTrip { int value = 20 + 22\n int answer() { return value } }";
        CompileResult result = compiler.compile(CompileRequest.builder("RoundTrip").source("main.vls", source).build());
        assertTrue(result.success(), "Compiler diagnostics: " + result.diagnostics());
        CompiledModule module = DefaultScriptCompiler.deserializeBytecode(result.bytecode(), List.of(), PermissionSet.empty(), PermissionSet.empty());
        assertNotNull(module);
        assertEquals("Ava", module.author());
        assertEquals("test", module.description());
        assertEquals(1, module.fieldInitializers().size());
        assertEquals(42, ((Number) module.fieldInitializers().get(0).initialValue().boxed()).intValue());
        byte[] damaged = Arrays.copyOf(result.bytecode(), 12);
        assertNull(DefaultScriptCompiler.deserializeBytecode(damaged, List.of(), PermissionSet.empty(), PermissionSet.empty()));
    }

    @Test
    void schedulerRetiresCompletedFibersAndReleasesLimits() {
        CompiledModule module = compile("@Script(name=\"T\", version=\"1\")\nscript T { int answer() { return 42 } }");
        VeloraLimits limits = VeloraLimits.builder().maxFibersPerScript(1).build();
        ScriptScheduler scheduler = new ScriptScheduler(limits, api, new RuntimeErrorStore(10));
        ScriptFiber first = scheduler.spawnFiber("T", module.functionByName("answer").index(), new ScriptValue[0]);
        assertNotNull(first);
        scheduler.tick(System.nanoTime(), Map.of("T", module), Map.of("T", List.of()));
        assertTrue(scheduler.fibersForScript("T").isEmpty());
        assertEquals(0, scheduler.resources("T").fibers());
        assertEquals(0L, scheduler.resources("T").memoryUsed());
        assertNotNull(scheduler.spawnFiber("T", module.functionByName("answer").index(), new ScriptValue[0]));
    }

    @Test
    void consolePrintWritesHostAndDebugLog() {
        List<String> output = new ArrayList<>();
        VeloraEngine engine = Velora.builder().host(host(output)).build();
        engine.freeze();
        DefaultScriptCompiler compiler = (DefaultScriptCompiler) engine.compiler();
        CompiledModule module = compiler.compileToModule(CompileRequest.builder("ConsoleT")
                .source("main.vls", "@Script(name=\"ConsoleT\", version=\"1\")\nscript ConsoleT { void answer() { console.print(\"hello\") } }").build());
        assertNotNull(module);
        VmExecutionResult result = new VirtualMachine(engine.api(), List.of(), 100_000)
                .execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
        assertTrue(result.success());
        assertTrue(output.contains("hello"));
        assertTrue(engine.debug().logs("ConsoleT").stream().anyMatch(log -> log.message().equals("hello")));
    }

    @Test
    void apiHandleReturnTypeIsValidatedAtRuntime() {
        class Expected {}
        class Wrong {}
        var handleType = types.handle("ExpectedRef", Expected.class);
        api.namespace("objects", ns -> ns.function("good", handleType, ctx -> new Expected()).description("good").categoryId("test"));
        api.namespace("objects", ns -> ns.function("bad", handleType, ctx -> new Wrong()).description("bad").categoryId("test"));
        CompiledModule good = compile("@Script(name=\"T\", version=\"1\")\nscript T { ExpectedRef answer() { return objects.good() } }");
        VmExecutionResult goodResult = execute(good);
        assertTrue(goodResult.success());
        assertTrue(goodResult.returnValue() instanceof HandleValue);
        CompiledModule bad = compile("@Script(name=\"T2\", version=\"1\")\nscript T2 { ExpectedRef answer() { return objects.bad() } }");
        VmExecutionResult badResult = execute(bad);
        assertFalse(badResult.success());
        assertTrue(badResult.error().message().contains("Handle type mismatch"));
    }

    @Test
    void limitsRejectInvalidConfigurations() {
        assertThrows(IllegalArgumentException.class, () -> VeloraLimits.builder().maxStringLength(0).build());
        assertThrows(IllegalArgumentException.class, () -> VeloraLimits.builder().instructionsPerFiberTick(10_000).instructionsPerScriptTick(5_000).build());
    }

    @Test
    void durationLiteralPreservesUnit() {
        CompiledModule module = compile("@Script(name=\"T\", version=\"1\")\nscript T { Duration answer() { return 1.5.seconds } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(1_500_000_000L, ((Number) result.returnValue().boxed()).longValue());
    }

    @Test
    void taskTypingRejectsFakeIntegerTask() {
        List<Diagnostic> diagnostics = semanticDiagnostics("@Script(name=\"T\", version=\"1\")\nscript T { int child() { return 42 } async int run() { int task = spawn child() return await(task) } }");
        assertTrue(diagnostics.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE));
        compile("@Script(name=\"T2\", version=\"1\")\nscript T2 { int child() { return 42 } async int run() { Task<int> task = spawn child() return await(task) } }");
    }

    @Test
    void asyncOperationsAreRejectedFromSyncFunctions() {
        api.namespace("asyncApi", ns -> ns.suspendFunction("value", VeloraTypes.INT, p -> {}, ctx -> 1));
        List<Diagnostic> await = semanticDiagnostics("@Script(name=\"T\", version=\"1\")\nscript T { int child() { return 1 } int answer() { Task<int> task = spawn child() return await(task) } }");
        assertTrue(await.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_ASYNC_VIOLATION));
        List<Diagnostic> yield = semanticDiagnostics("@Script(name=\"T2\", version=\"1\")\nscript T2 { int answer() { yield() return 1 } }");
        assertTrue(yield.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_ASYNC_VIOLATION));
        List<Diagnostic> apiCall = semanticDiagnostics("@Script(name=\"T3\", version=\"1\")\nscript T3 { int answer() { return asyncApi.value() } }");
        assertTrue(apiCall.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_ASYNC_VIOLATION));
    }

    @Test
    void setValuesCrossHostBoundaryAndExposeSize() {
        ScriptValue value = ScriptValue.fromJava(new LinkedHashSet<>(List.of(1, 2, 3)));
        assertTrue(value instanceof SetValue);
        assertEquals(Set.of(1, 2, 3), value.boxed());
        api.namespace("sets", ns -> ns.function("values", VeloraTypes.set(VeloraTypes.INT), ctx -> new LinkedHashSet<>(List.of(1, 2, 3))));
        CompiledModule module = compile("@Script(name=\"T\", version=\"1\")\nscript T { int answer() { return sets.values().size } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(3, ((Number) result.returnValue().boxed()).intValue());
    }

    @Test
    void languageServiceUsesV2SyntaxAndOneBasedCoordinates() {
        DefaultEditorSession editor = new DefaultEditorSession("T", "main.vls");
        editor.updateText("script T {\n    int answer() { return 1 }\n    String text = \"answer { }\"\n    // answer\n    int run() { return answer() }\n}");
        List<String> labels = editor.completions(1, 1).stream().map(io.velora.api.language.CompletionItem::label).toList();
        assertTrue(labels.contains("script"));
        assertTrue(labels.contains("Task"));
        assertTrue(labels.contains("await"));
        assertFalse(labels.contains("fun"));
        assertFalse(labels.contains("val"));
        assertEquals(1, editor.snapshot().tokens().get(0).column());
        assertEquals(2, editor.definition(5, 25).orElseThrow().line());
        assertEquals(9, editor.definition(5, 25).orElseThrow().column());
        assertTrue(editor.hover(1, 2).orElseThrow().content().contains("Declares a Velora script"));
        assertEquals(2, editor.rename("answer", "result").size());
        editor.close();
    }

    @Test
    void annotatedBindingsExposeParametersAndTypedClientValues() {
        class Entity {}
        Entity entity = new Entity();
        types.handle("EntityRef", Entity.class);
        @VeloraNamespace("ann")
        class Bindings {
            @VeloraFunction(name = "sum") public int sum(int a, int b) { return a + b; }
            @VeloraFunction(name = "echoDuration") public Duration echoDuration(Duration value) { return value; }
            @VeloraFunction(name = "values") public List<Integer> values() { return List.of(1, 2, 3); }
            @VeloraFunction(name = "entity") public Entity entity() { return entity; }
            @VeloraProperty(name = "scriptId") public String scriptId(io.velora.api.function.FunctionContext context) { return context.scriptId(); }
        }
        api.registerAnnotated(new Bindings());
        assertEquals(2, api.find("ann", "sum").parameters().size());
        assertEquals(VeloraTypes.INT, api.find("ann", "sum").parameters().get(0).type());
        api.freeze();
        CompiledModule module = compile("@Script(name=\"T\", version=\"1\")\nscript T { int answer() { return ann.sum(20, 22) } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(42, ((Number) result.returnValue().boxed()).intValue());
        CompiledModule durationModule = compile("@Script(name=\"D\", version=\"1\")\nscript D { Duration answer() { return ann.echoDuration(2.seconds) } }");
        VmExecutionResult durationResult = new VirtualMachine(api, List.of(), 100_000).execute(durationModule, durationModule.functionByName("answer").index(), new ScriptValue[0]);
        assertTrue(durationResult.success());
        assertEquals(2_000_000_000L, ((Number) durationResult.returnValue().boxed()).longValue());
        CompiledModule handleModule = compile("@Script(name=\"H\", version=\"1\")\nscript H { EntityRef answer() { return ann.entity() } }");
        VmExecutionResult handleResult = new VirtualMachine(api, List.of(), 100_000).execute(handleModule, handleModule.functionByName("answer").index(), new ScriptValue[0]);
        assertTrue(handleResult.success());
        assertTrue(entity == handleResult.returnValue().boxed());
    }

    @Test
    void annotatedBindingsRejectUnsupportedAndInvalidPropertyShapes() {
        @VeloraNamespace("bad")
        class Unsupported { @VeloraFunction(name = "value") public Object value() { return new Object(); } }
        @VeloraNamespace("badprop")
        class BadProperty { @VeloraProperty(name = "value") public int value(int ignored) { return ignored; } }
        assertThrows(BindingValidationException.class, () -> api.registerAnnotated(new Unsupported()));
        assertThrows(BindingValidationException.class, () -> api.registerAnnotated(new BadProperty()));
    }

    @Test
    void settingStoreNormalizesAndEnforcesAllRuntimeConstraints() {
        SettingDescriptor number = new SettingDescriptor("speed", "Speed", VeloraTypes.DOUBLE, 1.0, null, null, null, 0, false, false, false, null,
                List.of(SettingDescriptor.Constraint.range(0.0, 10.0), SettingDescriptor.Constraint.step(0.5)), 0);
        SettingDescriptor text = new SettingDescriptor("name", "Name", VeloraTypes.STRING, "abc", null, null, null, 0, false, false, false, null,
                List.of(SettingDescriptor.Constraint.maxLength(5), SettingDescriptor.Constraint.pattern("[a-z]+")), 1);
        SettingStore store = new SettingStore(List.of(number, text));
        store.set("speed", SettingValue.ofInt(4));
        assertEquals(VeloraTypes.DOUBLE, store.get("speed").type());
        assertEquals(4.0, ((Number) store.get("speed").value()).doubleValue());
        assertThrows(IllegalArgumentException.class, () -> store.set("speed", SettingValue.ofDouble(4.25)));
        assertThrows(IllegalArgumentException.class, () -> store.set("speed", SettingValue.ofDouble(11.0)));
        assertThrows(IllegalArgumentException.class, () -> store.set("name", SettingValue.ofString("ABC")));
        assertThrows(IllegalArgumentException.class, () -> store.set("name", SettingValue.ofString("toolong")));
        assertEquals(1, store.applySnapshot(Map.of("speed", SettingValue.ofDouble(2.5), "name", SettingValue.ofString("BAD"))));
    }

    @Test
    void formatterIgnoresBracesInsideStringsAndReturnsNoEditWhenStable() {
        DefaultEditorSession editor = new DefaultEditorSession("T", "main.vls");
        String source = "script T {\n    String text = \"} {\"\n    int answer() {\n        return 1\n    }\n}";
        editor.updateText(source);
        assertTrue(editor.format().isEmpty());
        editor.close();
    }

    private CompiledModule withFunction(CompiledModule base, CompiledFunction function) {
        return new CompiledModule(base.scriptId(), base.scriptName(), base.version(), base.languageVersion(), base.sourceHash(), base.registryHash(),
                base.constantPool(), List.of(function), base.settings(), base.persistentFieldIds(), base.persistentFieldTypes(), base.persistentFieldIndices(),
                base.persistentFieldIsStatic(), base.requiredPermissions(), base.maximumPermissions(), base.lifecycleHooks(), base.eventHandlers(),
                base.fieldInitializers(), base.author(), base.description());
    }

    private VeloraHost host(List<String> output) {
        return new VeloraHost() {
            @Override public String id() { return "test"; }
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
                @Override public void debug(String message) { output.add(message); }
                @Override public void info(String message) { output.add(message); }
                @Override public void warn(String message) { output.add(message); }
                @Override public void error(String message, Throwable error) { output.add(message); }
            }; }
            @Override public VeloraFileSystem fileSystem() { return null; }
        };
    }
}
