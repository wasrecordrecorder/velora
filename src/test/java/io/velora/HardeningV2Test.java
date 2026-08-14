package io.velora;

import io.velora.api.Velora;
import io.velora.api.VeloraEngine;
import io.velora.api.VeloraLimits;
import io.velora.api.compiler.*;
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
    private DefaultConstantRegistry constants;
    private DefaultApiRegistry api;

    @BeforeEach
    void setUp() {
        types = new DefaultTypeRegistry();
        settings = new DefaultSettingRegistry();
        constants = new DefaultConstantRegistry();
        api = new DefaultApiRegistry(types);
    }

    private CompiledModule compile(String source) {
        LexerResult lex = new Lexer(source, "main.vls").lex();
        assertTrue(lex.diagnostics().isEmpty(), "Lexer errors: " + lex.diagnostics());
        ParseResult parse = Parser.parse(source, "main.vls");
        assertTrue(parse.diagnostics().isEmpty(), "Parser errors: " + parse.diagnostics());
        SemanticAnalyzer analyzer = new SemanticAnalyzer(types, settings, api, constants);
        ResolvedScript resolved = analyzer.analyze(parse.scriptNode());
        assertTrue(analyzer.diagnostics().isEmpty(), "Semantic errors: " + analyzer.diagnostics());
        IrModule ir = new IrBuilder(resolved, api).build();
        assertTrue(new IrVerifier().verify(ir).isEmpty(), "IR errors: " + new IrVerifier().verify(ir));
        CompiledModule module = new BytecodeWriter().write(ir);
        assertTrue(new BytecodeVerifier().verify(module).stream().noneMatch(Diagnostic::isError));
        return module;
    }

    private List<Diagnostic> semanticDiagnostics(String source) {
        ParseResult parse = Parser.parse(source, "main.vls");
        assertNotNull(parse.scriptNode());
        SemanticAnalyzer analyzer = new SemanticAnalyzer(types, settings, api, constants);
        analyzer.analyze(parse.scriptNode());
        return analyzer.diagnostics();
    }

    private VmExecutionResult execute(CompiledModule module) {
        return new VirtualMachine(api, List.of(), 500_000).execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
    }

    private DefaultScriptCompiler compiler() {
        return new DefaultScriptCompiler(types, settings, api, constants);
    }

    @Test
    void floatArithmeticAndUnaryMinus() {
        CompiledModule module = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { float answer() { return -(10.0f - 2.0f * 3.0f / 2.0f) } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(-7.0, ((Number) result.returnValue().boxed()).doubleValue());
    }

    @Test
    void mixedNumericEqualityUsesNumericValue() {
        CompiledModule module = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { boolean answer() { return 1 == 1L } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(true, result.returnValue().boxed());
    }

    @Test
    void stringConcatenationHonorsRuntimeLimit() {
        CompiledModule module = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { String answer() { return \"abc\" + \"def\" } }");
        VmExecutionResult result = new VirtualMachine(api, List.of(), null, 100_000, 128, 5, 100, 8)
                .execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
        assertFalse(result.success());
        assertEquals(DiagnosticCode.RUNTIME_RESOURCE_LIMIT, result.error().code());
    }

    @Test
    void invalidFunctionIndexReturnsVmFailure() {
        CompiledModule module = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { return 42 } }");
        VmExecutionResult result = new VirtualMachine(api, List.of(), 100_000).execute(module, 999, new ScriptValue[0]);
        assertFalse(result.success());
        assertTrue(result.error().message().contains("Function not found"));
    }

    @Test
    void semanticRejectsInvalidOperatorsAndAssignments() {
        List<Diagnostic> operator = semanticDiagnostics("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { return true + false } }");
        assertTrue(operator.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
        List<Diagnostic> assignment = semanticDiagnostics("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { int x = 1\n x = \"bad\"\n return x } }");
        assertTrue(assignment.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
    }

    @Test
    void semanticRejectsRuntimeFieldInitializer() {
        List<Diagnostic> diagnostics = semanticDiagnostics("@Script(\"T\")\n@Version(\"1\")\nscript T { int make() { return 1 }\n int value = make()\n int answer() { return value } }");
        assertTrue(diagnostics.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_NON_CONSTANT_FIELD_INIT));
    }

    @Test
    void constantExpressionFieldInitializerExecutes() {
        CompiledModule module = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { int value = 20 + 22\n int answer() { return value } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(42, ((Number) result.returnValue().boxed()).intValue());
    }

    @Test
    void bytecodeVerifierRejectsUnderflowBadJumpAndFallthrough() {
        CompiledModule base = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { return 1 } }");
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
        CompiledModule base = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { return 1 } }");
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
                .source("main.vls", "@Script(\"FoldT\")\n@Version(\"1\")\nscript FoldT { int answer() { return 20 + 22 } }").build());
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
                .source("main.vls", "@Script(\"JumpFoldT\")\n@Version(\"1\")\nscript JumpFoldT { int answer() { int x = 20 + 22\n if (x == 42) { return 1 + 1 } return 0 } }").build());
        assertNotNull(module);
        VmExecutionResult result = new VirtualMachine(api, List.of(), 100_000).execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
        assertTrue(result.success());
        assertEquals(2, ((Number) result.returnValue().boxed()).intValue());
    }

    @Test
    void compilerCacheOnlyUsesContentAndRegistryHash() {
        DefaultScriptCompiler compiler = compiler();
        String source = "@Script(\"CacheT\")\n@Version(\"1\")\nscript CacheT { int answer() { return 42 } }";
        CompileResult full = compiler.compile(CompileRequest.builder("CacheT").source("main.vls", source).mode(CompileMode.FULL).build());
        CompileResult hit = compiler.compile(CompileRequest.builder("CacheT").source("main.vls", source).mode(CompileMode.CACHE_ONLY).build());
        CompileResult miss = compiler.compile(CompileRequest.builder("CacheT").source("main.vls", source.replace("42", "43")).mode(CompileMode.CACHE_ONLY).build());
        assertTrue(full.success());
        assertTrue(hit.success());
        assertFalse(miss.success());
        assertTrue(miss.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.COMPILER_CACHE_MISS));
    }

    @Test
    void compilerUsesRequestScriptIdAndKeepsMultifileFunctionIndicesStable() {
        DefaultScriptCompiler compiler = compiler();
        CompileRequest request = CompileRequest.builder("folder-id")
                .source("main.vls", "@Script(\"Display Name\")\n@Version(\"1\")\nscript SourceName { int run() { return helper() } @Run start() { run() } }")
                .source("helper.vls", "int helper() { return 42 }")
                .build();
        CompileResult result = compiler.compile(request);
        assertTrue(result.success(), "Diagnostics: " + result.diagnostics());
        CompiledModule module = compiler.compileToModule(request);
        assertEquals("folder-id", module.scriptId());
        assertEquals("Display Name", module.scriptName());
        assertEquals(42, ((Number) new VirtualMachine(api, List.of(), 100_000)
                .execute(module, module.functionByName("run").index(), new ScriptValue[0]).returnValue().boxed()).intValue());
    }

    @Test
    void compilerSourceDetectionIgnoresScriptTextInsideString() {
        DefaultScriptCompiler compiler = compiler();
        String main = "@Script(\"Multi\")\n@Version(\"1\")\nscript Multi { int answer() { return helper() } }";
        String helper = "int helper() { String text = \"script Fake {}\"\n return 42 }";
        CompileResult result = compiler.compile(CompileRequest.builder("Multi").source("main.vls", main).source("helper.vls", helper).build());
        assertTrue(result.success(), "Compiler diagnostics: " + result.diagnostics());
    }

    @Test
    void bytecodeRoundTripKeepsMetadataAndFieldInitializers() {
        DefaultScriptCompiler compiler = compiler();
        String source = "@Script(\"RoundTrip\")\n@Version(\"2\")\n@Author(\"Ava\")\n@Description(\"test\")\nscript RoundTrip { int value = 20 + 22\n int answer() { return value } }";
        CompileResult result = compiler.compile(CompileRequest.builder("RoundTrip").source("main.vls", source).build());
        assertTrue(result.success(), "Compiler diagnostics: " + result.diagnostics());
        CompiledModule module = DefaultScriptCompiler.deserializeBytecode(result.bytecode(), List.of());
        assertNotNull(module);
        assertEquals("Ava", module.author());
        assertEquals("test", module.description());
        assertEquals(1, module.fieldInitializers().size());
        assertEquals(42, ((Number) module.fieldInitializers().get(0).initialValue().boxed()).intValue());
        byte[] damaged = Arrays.copyOf(result.bytecode(), 12);
        assertNull(DefaultScriptCompiler.deserializeBytecode(damaged, List.of()));
    }

    @Test
    void schedulerRetiresCompletedFibersAndReleasesLimits() {
        CompiledModule module = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { return 42 } }");
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
                .source("main.vls", "@Script(\"ConsoleT\")\n@Version(\"1\")\nscript ConsoleT { answer() { console.print(\"hello\") } }").build());
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
        CompiledModule good = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { ExpectedRef answer() { return objects.good() } }");
        VmExecutionResult goodResult = execute(good);
        assertTrue(goodResult.success());
        assertTrue(goodResult.returnValue() instanceof HandleValue);
        CompiledModule bad = compile("@Script(\"T2\")\n@Version(\"1\")\nscript T2 { ExpectedRef answer() { return objects.bad() } }");
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
        CompiledModule module = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { Duration answer() { return 1.5.seconds } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(1_500_000_000L, ((Number) result.returnValue().boxed()).longValue());
    }

    @Test
    void taskTypingRejectsFakeIntegerTask() {
        List<Diagnostic> diagnostics = semanticDiagnostics("@Script(\"T\")\n@Version(\"1\")\nscript T { int child() { return 42 } async int run() { int task = spawn child() return await(task) } }");
        assertTrue(diagnostics.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE));
        compile("@Script(\"T2\")\n@Version(\"1\")\nscript T2 { int child() { return 42 } async int run() { Task<int> task = spawn child() return await(task) } }");
    }

    @Test
    void asyncOperationsAreRejectedFromSyncFunctions() {
        api.namespace("asyncApi", ns -> ns.suspendFunction("value", VeloraTypes.INT, p -> {}, ctx -> 1));
        List<Diagnostic> await = semanticDiagnostics("@Script(\"T\")\n@Version(\"1\")\nscript T { int child() { return 1 } int answer() { Task<int> task = spawn child() return await(task) } }");
        assertTrue(await.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_ASYNC_VIOLATION));
        List<Diagnostic> yield = semanticDiagnostics("@Script(\"T2\")\n@Version(\"1\")\nscript T2 { int answer() { yield() return 1 } }");
        assertTrue(yield.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_ASYNC_VIOLATION));
        List<Diagnostic> apiCall = semanticDiagnostics("@Script(\"T3\")\n@Version(\"1\")\nscript T3 { int answer() { return asyncApi.value() } }");
        assertTrue(apiCall.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_ASYNC_VIOLATION));
    }

    @Test
    void setValuesCrossHostBoundaryAndExposeSize() {
        ScriptValue value = ScriptValue.fromJava(new LinkedHashSet<>(List.of(1, 2, 3)));
        assertTrue(value instanceof SetValue);
        assertEquals(Set.of(1, 2, 3), value.boxed());
        api.namespace("sets", ns -> ns.function("values", VeloraTypes.set(VeloraTypes.INT), ctx -> new LinkedHashSet<>(List.of(1, 2, 3))));
        CompiledModule module = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { return sets.values().size } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(3, ((Number) result.returnValue().boxed()).intValue());
    }

    @Test
    void languageServiceUsesV2SyntaxAndOneBasedCoordinates() {
        DefaultEditorSession editor = new DefaultEditorSession("T", "main.vls");
        editor.updateText("@Script(\"T\")\nscript T {\n    answer() { return 1 }\n    text = \"answer { }\"\n    // answer\n    run() { return answer() }\n}");
        List<String> labels = editor.completions(1, 1).stream().map(io.velora.api.language.CompletionItem::label).toList();
        assertTrue(labels.contains("script"));
        assertTrue(labels.contains("Task"));
        assertTrue(labels.contains("await"));
        assertFalse(labels.contains("fun"));
        assertFalse(labels.contains("val"));
        assertEquals(1, editor.snapshot().tokens().get(0).column());
        assertEquals(3, editor.definition(6, 24).orElseThrow().line());
        assertEquals(5, editor.definition(6, 24).orElseThrow().column());
        assertTrue(editor.hover(2, 2).orElseThrow().content().contains("Declares a Velora script"));
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
        CompiledModule module = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { return ann.sum(20, 22) } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(42, ((Number) result.returnValue().boxed()).intValue());
        CompiledModule durationModule = compile("@Script(\"D\")\n@Version(\"1\")\nscript D { Duration answer() { return ann.echoDuration(2.seconds) } }");
        VmExecutionResult durationResult = new VirtualMachine(api, List.of(), 100_000).execute(durationModule, durationModule.functionByName("answer").index(), new ScriptValue[0]);
        assertTrue(durationResult.success());
        assertEquals(2_000_000_000L, ((Number) durationResult.returnValue().boxed()).longValue());
        CompiledModule handleModule = compile("@Script(\"H\")\n@Version(\"1\")\nscript H { EntityRef answer() { return ann.entity() } }");
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


    @Test
    void lexicalScopesWhenSafeAccessAndStringIndexExecuteCorrectly() {
        CompiledModule scopes = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { int x = 1\n if (true) { int x = 42 }\n when (2) { 1 -> { return 0 } 2 -> { } else -> { return 0 } }\n return x } }");
        VmExecutionResult scopeResult = execute(scopes);
        assertTrue(scopeResult.success());
        assertEquals(1, ((Number) scopeResult.returnValue().boxed()).intValue());
        CompiledModule stringIndex = compile("@Script(\"T2\")\n@Version(\"1\")\nscript T2 { char answer() { return \"abc\"[1] } }");
        VmExecutionResult stringResult = execute(stringIndex);
        assertTrue(stringResult.success());
        assertEquals('b', stringResult.returnValue().boxed());
        CompiledModule safe = compile("@Script(\"T3\")\n@Version(\"1\")\nscript T3 { int? answer() { String? value = null\n return value?.length } }");
        assertTrue(execute(safe).returnValue().isNull());
        CompiledModule safeElvis = compiler().compileToModule(CompileRequest.builder("T4").source("main.vls",
                "@Script(\"T4\")\n@Version(\"1\")\nscript T4 { int answer() { String? value = null\n return value?.length ?: 0 } }").build());
        assertNotNull(safeElvis);
        assertEquals(0, ((Number) new VirtualMachine(api, List.of(), 100_000)
                .execute(safeElvis, safeElvis.functionByName("answer").index(), new ScriptValue[0]).returnValue().boxed()).intValue());
    }

    @Test
    void nullableUnsafeAccessAndForTypeMismatchAreCompileErrors() {
        List<Diagnostic> nullable = semanticDiagnostics("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { String? value = null\n return value.length } }");
        assertTrue(nullable.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
        List<Diagnostic> loop = semanticDiagnostics("@Script(\"T2\")\n@Version(\"1\")\nscript T2 { int answer() { List<int> values = [1]\n for (String value in values) { }\n return 1 } }");
        assertTrue(loop.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
    }

    @Test
    void indexErrorsAndIntegerOverflowUseDedicatedRuntimeCodes() {
        CompiledModule index = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { List<int> values = [1]\n return values[2] } }");
        VmExecutionResult indexResult = execute(index);
        assertFalse(indexResult.success());
        assertEquals(DiagnosticCode.RUNTIME_INDEX_OUT_OF_BOUNDS, indexResult.error().code());
        CompiledModule overflow = compile("@Script(\"T2\")\n@Version(\"1\")\nscript T2 { int answer() { int value = 2147483647\n return value + 1 } }");
        VmExecutionResult overflowResult = execute(overflow);
        assertFalse(overflowResult.success());
        assertEquals(DiagnosticCode.RUNTIME_ARITHMETIC_OVERFLOW, overflowResult.error().code());
    }

    @Test
    void qualifiedHostConstantsAndEnumsPreserveTypedValues() {
        enum Mode { FIRST, SECOND }
        constants.register("Answers", "VALUE", VeloraTypes.INT, 42);
        var modeType = types.enumType("Mode", Mode.class, List.of(
                new io.velora.api.type.EnumType.Constant("FIRST", Mode.FIRST),
                new io.velora.api.type.EnumType.Constant("SECOND", Mode.SECOND)));
        DefaultScriptCompiler compiler = compiler();
        CompileRequest numberRequest = CompileRequest.builder("ConstT")
                .source("main.vls", "@Script(\"ConstT\")\n@Version(\"1\")\nscript ConstT { int answer() { return Answers.VALUE } }").build();
        CompileRequest modeRequest = CompileRequest.builder("EnumT")
                .source("main.vls", "@Script(\"EnumT\")\n@Version(\"1\")\nscript EnumT { Mode answer() { return Mode.SECOND } }").build();
        CompileResult numberResult = compiler.compile(numberRequest);
        CompileResult modeResult = compiler.compile(modeRequest);
        assertTrue(numberResult.success(), "Diagnostics: " + numberResult.diagnostics());
        assertTrue(modeResult.success(), "Diagnostics: " + modeResult.diagnostics());
        CompiledModule number = compiler.compileToModule(numberRequest);
        CompiledModule mode = compiler.compileToModule(modeRequest);
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), api, new RuntimeErrorStore(10), null, constants, types);
        ScriptFiber numberFiber = scheduler.spawnFiber("ConstT", number.functionByName("answer").index(), new ScriptValue[0]);
        scheduler.tick(System.nanoTime(), Map.of("ConstT", number), Map.of("ConstT", List.of()));
        assertEquals(42, ((Number) numberFiber.result().boxed()).intValue());
        ScriptFiber enumFiber = scheduler.spawnFiber("EnumT", mode.functionByName("answer").index(), new ScriptValue[0]);
        scheduler.tick(System.nanoTime(), Map.of("EnumT", mode), Map.of("EnumT", List.of()));
        assertEquals(Mode.SECOND, enumFiber.result().boxed());
        assertEquals(modeType.name(), "Mode");
    }

    @Test
    void annotatedBindingRegistrationIsAtomicAndProgrammaticThreadCostAreExposed() {
        api.namespace("atomic", ns -> ns.function("a", VeloraTypes.INT, ctx -> 1));
        @VeloraNamespace("atomic")
        class Binding {
            @VeloraFunction(name = "a") public int a() { return 1; }
            @VeloraFunction(name = "b") public int b() { return 2; }
        }
        assertThrows(IllegalStateException.class, () -> api.registerAnnotated(new Binding()));
        assertNull(api.find("atomic", "b"));
        api.namespace("worker", ns -> ns.suspendFunction("value", VeloraTypes.INT, p -> {}, ctx -> 42)
                .thread(io.velora.api.function.ScriptThread.WORKER).cost(7));
        assertEquals(io.velora.api.function.ScriptThread.WORKER, api.find("worker", "value").thread());
        assertEquals(7, api.find("worker", "value").cost());
    }

    @Test
    void workerBindingsActuallyExecuteThroughWorkerExecutor() {
        int[] executions = {0};
        api.namespace("worker", ns -> ns.suspendFunction("value", VeloraTypes.INT, p -> {}, ctx -> 42)
                .thread(io.velora.api.function.ScriptThread.WORKER));
        api.freeze();
        CompiledModule module = compile("@Script(\"WorkerT\")\n@Version(\"1\")\nscript WorkerT { async int answer() { return worker.value() } }");
        WorkerExecutor workers = new WorkerExecutor() {
            @Override public void execute(Runnable action) { executions[0]++; action.run(); }
            @Override public void shutdown() {}
        };
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), api, new RuntimeErrorStore(10), workers);
        ScriptFiber fiber = scheduler.spawnFiber("WorkerT", module.functionByName("answer").index(), new ScriptValue[0]);
        scheduler.tick(System.nanoTime(), Map.of("WorkerT", module), Map.of("WorkerT", List.of()));
        assertEquals(1, executions[0]);
        assertEquals(42, ((Number) fiber.result().boxed()).intValue());
    }

    @Test
    void eventDescriptorsAreValidatedAtCompileTime() {
        List<String> output = new ArrayList<>();
        var events = new io.velora.internal.event.DefaultEventRegistry(host(output));
        events.register(io.velora.api.event.EventDescriptor.builder("client.tick")
                .scriptName("ClientTick").payloadType(VeloraTypes.INT).build());
        DefaultScriptCompiler compiler = new DefaultScriptCompiler(types, settings, api, constants, events);
        CompileResult good = compiler.compile(CompileRequest.builder("EventT").source("main.vls",
                "@Script(\"EventT\")\n@Version(\"1\")\nscript EventT { @ClientTick ClientTick(int value) { } }").build());
        assertTrue(good.success(), "Diagnostics: " + good.diagnostics());
        CompiledModule module = compiler.compileToModule(CompileRequest.builder("EventT").source("main.vls",
                "@Script(\"EventT\")\n@Version(\"1\")\nscript EventT { @ClientTick ClientTick(int value) { } }").build());
        CompileResult bad = compiler.compile(CompileRequest.builder("BadEventT").source("main.vls",
                "@Script(\"BadEventT\")\n@Version(\"1\")\nscript BadEventT { @ClientTick ClientTick(String value) { } }").build());
        assertFalse(bad.success());
        assertTrue(bad.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
    }

    @Test
    void languageServiceCompletesAndDescribesRegisteredBindings() {
        api.namespace("client", ns -> ns.function("move", VeloraTypes.INT, p -> p.required("distance", VeloraTypes.INT), ctx -> 1)
                .description("Moves the client").categoryId("client"));
        var language = new io.velora.internal.language.DefaultLanguageService(api, types, null, settings, constants);
        var editor = language.openEditor("T", "main.vls");
        editor.updateText("script T { int answer() { return client.mo } }");
        assertTrue(editor.completions(1, 43).stream().anyMatch(item -> item.label().equals("move")));
        editor.updateText("script T { int answer() { return client.move(1) } }");
        assertTrue(editor.signatureHelp(1, 47).orElseThrow().parameters().stream().anyMatch(parameter -> parameter.name().equals("distance")));
        assertTrue(editor.hover(1, 41).orElseThrow().content().contains("Moves the client"));
        language.close();
    }

    @Test
    void compilerRejectsUnsafeAndDuplicatePathsAndHashesSourcesDeterministically() {
        DefaultScriptCompiler compiler = compiler();
        String main = "@Script(\"Multi\")\n@Version(\"1\")\nscript Multi { int answer() { return helper() } }";
        String helper = "int helper() { return 42 }";
        CompileResult traversal = compiler.compile(CompileRequest.builder("Multi").source("../main.vls", main).build());
        assertFalse(traversal.success());
        assertTrue(traversal.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.COMPILER_PATH_TRAVERSAL));
        CompileResult duplicate = compiler.compile(CompileRequest.builder("Multi").source("src/main.vls", main).source("src/./main.vls", helper).build());
        assertFalse(duplicate.success());
        CompileResult first = compiler.compile(CompileRequest.builder("MultiA").source("z/helper.vls", helper).source("a/main.vls", main).build());
        CompileResult second = compiler.compile(CompileRequest.builder("MultiB").source("a/main.vls", main).source("z/helper.vls", helper).build());
        assertTrue(first.success());
        assertTrue(second.success());
        assertEquals(first.sourceHash(), second.sourceHash());
    }

    @Test
    void invalidAnnotationPlacementAndMetadataAreRejected() {
        List<Diagnostic> misplaced = semanticDiagnostics("@Script(\"T\")\nscript T { @Unknown int answer() { return 1 } }");
        assertTrue(misplaced.stream().anyMatch(Diagnostic::isError));
        List<Diagnostic> metadata = semanticDiagnostics("@Script(\"T\", unknown=\"x\")\nscript T { int answer() { return 1 } }");
        assertTrue(metadata.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_INVALID_ARGUMENT));
    }

    @Test
    void elvisTypingAndUnknownMembersAreValidated() {
        CompiledModule module = compile("@Script(\"ElvisT\")\n@Version(\"1\")\nscript ElvisT { int answer() { String? value = null\n return (value ?: \"ok\").length } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(2, ((Number) result.returnValue().boxed()).intValue());
        List<Diagnostic> incompatible = semanticDiagnostics("@Script(\"BadElvis\")\n@Version(\"1\")\nscript BadElvis { int answer() { String? value = null\n return value ?: 1 } }");
        assertTrue(incompatible.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
        List<Diagnostic> member = semanticDiagnostics("@Script(\"BadMember\")\n@Version(\"1\")\nscript BadMember { int answer() { String value = \"x\"\n return value.lenght } }");
        assertTrue(member.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL));
    }

    @Test
    void bindingContextConvertsNumericWrappersAndNamedTypedArguments() {
        @VeloraNamespace("numbers")
        class Binding {
            @VeloraFunction(name = "echo") public Long echo(Long value) { return value; }
        }
        api.registerAnnotated(new Binding());
        CompiledModule module = compile("@Script(\"NumberBinding\")\n@Version(\"1\")\nscript NumberBinding { long answer() { return numbers.echo(42) } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success(), String.valueOf(result.error()));
        assertEquals(42L, ((Number) result.returnValue().boxed()).longValue());
        api.namespace("named", ns -> ns.function("read", VeloraTypes.LONG, p -> p.required("value", VeloraTypes.LONG), ctx -> ctx.argument("value", Long.class)));
        CompiledModule named = compile("@Script(\"NamedBinding\")\n@Version(\"1\")\nscript NamedBinding { long answer() { return named.read(42) } }");
        VmExecutionResult namedResult = execute(named);
        assertTrue(namedResult.success(), String.valueOf(namedResult.error()));
        assertEquals(42L, ((Number) namedResult.returnValue().boxed()).longValue());
    }

    @Test
    void hostDefaultsAreTypedValidatedAndExecutable() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        api.namespace("defaults", ns -> ns
                .function("duration", VeloraTypes.LONG, p -> p.optional("value", VeloraTypes.DURATION, Duration.ofSeconds(2)), ctx -> ctx.argument("value", Duration.class).toNanos())
                .function("uuid", VeloraTypes.STRING, p -> p.optional("value", VeloraTypes.UUID, id), ctx -> ctx.argument("value", UUID.class).toString()));
        CompiledModule duration = compile("@Script(\"DefaultsDuration\")\n@Version(\"1\")\nscript DefaultsDuration { long answer() { return defaults.duration() } }");
        VmExecutionResult durationResult = execute(duration);
        assertTrue(durationResult.success(), String.valueOf(durationResult.error()));
        assertEquals(Duration.ofSeconds(2).toNanos(), ((Number) durationResult.returnValue().boxed()).longValue());
        CompiledModule uuid = compile("@Script(\"DefaultsUuid\")\n@Version(\"1\")\nscript DefaultsUuid { String answer() { return defaults.uuid() } }");
        VmExecutionResult uuidResult = execute(uuid);
        assertTrue(uuidResult.success(), String.valueOf(uuidResult.error()));
        assertEquals(id.toString(), uuidResult.returnValue().boxed());
        assertThrows(IllegalArgumentException.class, () -> api.namespace("invalidDefaults", ns -> ns.function("bad", VeloraTypes.INT,
                p -> p.optional("optional", VeloraTypes.INT, 1).required("required", VeloraTypes.INT), ctx -> 0)));
        assertThrows(IllegalArgumentException.class, () -> api.namespace("invalidCharDefault", ns -> ns.function("bad", VeloraTypes.CHAR,
                p -> p.optional("value", VeloraTypes.CHAR, 'x'), ctx -> ctx.argument(0))));
    }

    @Test
    void failedExtensionsRollbackEveryMutableRegistry() {
        VeloraEngine engine = Velora.builder().host(host(new ArrayList<>())).build();
        engine.extensions().register(new io.velora.api.VeloraExtension() {
            @Override public String id() { return "broken"; }
            @Override public String version() { return "1"; }
            @Override public void register(io.velora.api.VeloraExtensionContext context) {
                context.types().handle("TemporaryType", Object.class);
                context.settings().register(io.velora.api.setting.SettingKind.named("TemporarySetting").resultType(VeloraTypes.INT).build());
                context.constants().register("Temporary", "VALUE", VeloraTypes.INT, 1);
                context.api().namespace("temporary", ns -> ns.function("value", VeloraTypes.INT, ctx -> 1));
                context.events().register(io.velora.api.event.EventDescriptor.builder("temporary.event").scriptName("TemporaryEvent").payloadType(VeloraTypes.UNIT).build());
                throw new IllegalStateException("boom");
            }
        });
        assertThrows(IllegalStateException.class, engine::freeze);
        assertNull(engine.types().find("TemporaryType"));
        assertNull(engine.settings().find("TemporarySetting"));
        assertNull(engine.constants().find("Temporary", "VALUE"));
        assertNull(engine.api().find("temporary", "value"));
        assertNull(engine.events().find("temporary.event"));
    }

    @Test
    void registriesAndTypeBuildersRejectAmbiguousDuplicateEntries() {
        constants.register("Answers", "VALUE", VeloraTypes.INT, 1);
        assertThrows(IllegalStateException.class, () -> constants.register("Answers", "VALUE", VeloraTypes.INT, 2));
        assertEquals(1, constants.all().size());
        var kind = io.velora.api.setting.SettingKind.named("SingleKind").resultType(VeloraTypes.INT).build();
        settings.register(kind);
        assertThrows(IllegalStateException.class, () -> settings.register(kind));
        assertThrows(IllegalArgumentException.class, () -> types.handle("bad.type", Object.class));
        assertThrows(IllegalArgumentException.class, () -> types.struct("BrokenStruct", Object.class, b -> b
                .property("value", VeloraTypes.INT, ignored -> 1)
                .property("value", VeloraTypes.INT, ignored -> 2)));
        assertThrows(IllegalArgumentException.class, () -> types.enumType("BrokenEnum", String.class, List.of(
                new io.velora.api.type.EnumType.Constant("A", "a"),
                new io.velora.api.type.EnumType.Constant("A", "b"))));
    }

    @Test
    void payloadlessEventsNeedNoFakeParameterAndCannotReturnIgnoredValues() {
        var events = new io.velora.internal.event.DefaultEventRegistry(host(new ArrayList<>()));
        events.register(io.velora.api.event.EventDescriptor.builder("client.tick").scriptName("ClientTick").payloadType(VeloraTypes.UNIT).build());
        events.register(io.velora.api.event.EventDescriptor.builder("client.value").scriptName("ClientValue").payloadType(VeloraTypes.INT).build());
        DefaultScriptCompiler compiler = new DefaultScriptCompiler(types, settings, api, constants, events);
        CompileResult good = compiler.compile(CompileRequest.builder("TickScript").source("main.vls",
                "@Script(\"TickScript\")\n@Version(\"1\")\nscript TickScript { @ClientTick ClientTick() { } }").build());
        assertTrue(good.success(), "Diagnostics: " + good.diagnostics());
        CompileResult ignoredReturn = compiler.compile(CompileRequest.builder("BadTickScript").source("main.vls",
                "@Script(\"BadTickScript\")\n@Version(\"1\")\nscript BadTickScript { @ClientTick ClientTick() { return false } }").build());
        assertFalse(ignoredReturn.success());
        assertTrue(ignoredReturn.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_VOID_RETURN_VALUE));
        CompileResult missingPayload = compiler.compile(CompileRequest.builder("BadValueScript").source("main.vls",
                "@Script(\"BadValueScript\")\n@Version(\"1\")\nscript BadValueScript { @ClientValue ClientValue() { } }").build());
        assertFalse(missingPayload.success());
        assertTrue(missingPayload.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_WRONG_ARITY));
    }

    @Test
    void languageVersionAndScriptMetadataAreStrict() {
        DefaultScriptCompiler compiler = compiler();
        CompileResult language = compiler.compile(CompileRequest.builder("Meta").languageVersion(1).source("main.vls",
                "@Script(\"Meta\")\nscript Meta { answer() { return 1 } }").build());
        assertFalse(language.success());
        assertTrue(language.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.COMPILER_UNSUPPORTED_VERSION));

        CompileResult unsupported = compiler.compile(CompileRequest.builder("Meta").source("main.vls",
                "@Script(\"Meta\")\n@Category(\"Combat\")\nscript Meta { answer() { return 1 } }").build());
        assertFalse(unsupported.success());
        assertTrue(unsupported.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION));

        CompileResult duplicate = compiler.compile(CompileRequest.builder("Meta").source("main.vls",
                "@Script(\"Meta\")\n@Version(\"1\")\n@Version(\"2\")\nscript Meta { answer() { return 1 } }").build());
        assertFalse(duplicate.success());
        assertTrue(duplicate.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_INVALID_ARGUMENT));
    }

    @Test
    void annotatedBindingsCanDisambiguateBuiltInVectorAndColorTypes() {
        @VeloraNamespace("typed")
        class Binding {
            @VeloraFunction(name = "vec3", returnType = "Vec3")
            public double[] vec3() { return new double[]{3.0, 4.0, 5.0}; }

            @VeloraProperty(name = "color", returnType = "Color")
            public int[] color() { return new int[]{10, 20, 30, 40}; }

            @VeloraFunction(name = "sum")
            public int sum(@io.velora.binding.annotation.VeloraParam(value = "point", type = "Vec3") double[] point,
                           @io.velora.binding.annotation.VeloraParam(value = "color", type = "Color") int[] color) {
                return (int) (point[0] + point[1] + point[2]) + color[0] + color[3];
            }
        }
        api.registerAnnotated(new Binding());
        CompiledModule module = compile("@Script(\"TypedBinding\")\n@Version(\"1\")\nscript TypedBinding { int answer() { Vec3 point = typed.vec3()\n Color color = typed.color\n return typed.sum(point, color) } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success(), String.valueOf(result.error()));
        assertEquals(62, ((Number) result.returnValue().boxed()).intValue());
    }

    private CompiledModule withFunction(CompiledModule base, CompiledFunction function) {
        return new CompiledModule(base.scriptId(), base.scriptName(), base.version(), base.languageVersion(), base.sourceHash(), base.registryHash(),
                base.constantPool(), List.of(function), base.settings(), base.persistentFieldIds(), base.persistentFieldTypes(), base.persistentFieldIndices(),
                base.persistentFieldIsStatic(), base.lifecycleHooks(), base.eventHandlers(),
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
