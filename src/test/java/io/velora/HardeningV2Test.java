package io.velora;

import io.velora.api.Velora;
import io.velora.api.VeloraEngine;
import io.velora.api.VeloraLimits;
import io.velora.api.compiler.*;
import io.velora.api.function.ScriptThread;
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
import io.velora.internal.persistence.BytecodeCache;
import io.velora.internal.persistence.EnabledScriptsStore;
import io.velora.internal.registry.*;
import io.velora.internal.scheduler.FiberState;
import io.velora.internal.scheduler.ScriptFiber;
import io.velora.internal.scheduler.ScriptScheduler;
import io.velora.internal.semantic.ResolvedScript;
import io.velora.internal.semantic.SemanticAnalyzer;
import io.velora.internal.setting.DefaultSettingRegistry;
import io.velora.internal.vm.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HardeningV2Test {
    private enum Mode { FAST, SLOW }

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
    void incrementalCompilationDoesNotTrustCallerContentHash() {
        DefaultScriptCompiler compiler = compiler();
        String first = "@Script(\"CacheT\")\n@Version(\"1\")\nscript CacheT { int answer() { return 1 } }";
        String second = "@Script(\"CacheT\")\n@Version(\"1\")\nscript CacheT { int answer() { return 2 } }";
        CompiledModule initial = compiler.compileToModule(new CompileRequest("cache-t", List.of(new SourceFile("main.vls", first, "stale")), CompileMode.FULL, 2, Map.of()));
        CompiledModule updated = compiler.compileToModule(new CompileRequest("cache-t", List.of(new SourceFile("main.vls", second, "stale")), CompileMode.INCREMENTAL, 2, Map.of()));
        VmExecutionResult result = execute(updated);
        assertTrue(result.success());
        assertEquals(2, ((Number) result.returnValue().boxed()).intValue());
        assertNotEquals(initial.sourceHash(), updated.sourceHash());
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
    void mutableFieldInitializersAreIsolatedFromCompiledModule() {
        CompiledModule module = compile("@Script(\"T\")\nscript T { values = list<int>() int add() { values.add(1) return values.size } int size() { return values.size } }");
        VirtualMachine first = new VirtualMachine(api, module.settings(), 100_000);
        VmExecutionResult mutated = first.execute(module, module.functionByName("add").index(), new ScriptValue[0]);
        assertTrue(mutated.success());
        assertEquals(1, ((Number) mutated.returnValue().boxed()).intValue());
        VirtualMachine second = new VirtualMachine(api, module.settings(), 100_000);
        VmExecutionResult fresh = second.execute(module, module.functionByName("size").index(), new ScriptValue[0]);
        assertTrue(fresh.success());
        assertEquals(0, ((Number) fresh.returnValue().boxed()).intValue());
    }

    @Test
    void settingLoadsRespectRuntimeValueLimits() {
        CompiledModule module = compile("@Script(\"T\")\nscript T { @Setting(\"Name\") name = \"abcdef\" String answer() { return name } }");
        VmExecutionResult result = new VirtualMachine(api, module.settings(), null, 100_000, 128, 3, 100, 8)
                .execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
        assertFalse(result.success());
        assertEquals(DiagnosticCode.RUNTIME_RESOURCE_LIMIT, result.error().code());
    }

    @Test
    void failedCollectionMutationsDoNotCommitInvalidState() {
        CompiledModule module = compile("@Script(\"T\")\nscript T { values = list<int>() ids = set<int>() scores = map<String, int>() " +
                "int fillList() { values.add(1) values.add(2) return values.size } int listSize() { return values.size } " +
                "int fillSet() { ids.add(1) ids.add(2) return ids.size } int setSize() { return ids.size } " +
                "int fillMap() { scores.put(\"a\", 1) scores.put(\"b\", 2) return scores.size } int mapSize() { return scores.size } }");
        VirtualMachine vm = new VirtualMachine(api, module.settings(), null, 100_000, 128, 1_000, 1, 8);
        for (String fill : List.of("fillList", "fillSet", "fillMap")) {
            VmExecutionResult result = vm.execute(module, module.functionByName(fill).index(), new ScriptValue[0]);
            assertFalse(result.success());
            assertEquals(DiagnosticCode.RUNTIME_RESOURCE_LIMIT, result.error().code());
        }
        assertEquals(1, ((Number) vm.execute(module, module.functionByName("listSize").index(), new ScriptValue[0]).returnValue().boxed()).intValue());
        assertEquals(1, ((Number) vm.execute(module, module.functionByName("setSize").index(), new ScriptValue[0]).returnValue().boxed()).intValue());
        assertEquals(1, ((Number) vm.execute(module, module.functionByName("mapSize").index(), new ScriptValue[0]).returnValue().boxed()).intValue());
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
    void constantEvaluationPreservesLongPrecisionAndRejectsIntegerOverflow() {
        CompiledModule module = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { boolean value = 9007199254740992L < 9007199254740993L boolean answer() { return value } }");
        VmExecutionResult result = execute(module);
        assertTrue(result.success());
        assertEquals(true, result.returnValue().boxed());
        List<Diagnostic> overflow = semanticDiagnostics("@Script(\"T\")\nscript T { int value = 2147483647 + 1 int answer() { return value } }");
        assertTrue(overflow.stream().anyMatch(Diagnostic::isError));
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
    void bytecodeVerifierValidatesRegisteredApiCalls() {
        api.namespace("client", ns -> ns.function("value", VeloraTypes.INT, p -> p.required("input", VeloraTypes.INT), ctx -> 1));
        api.freeze();
        CompiledModule base = compile("@Script(\"T\")\n@Version(\"1\")\nscript T { int answer() { return 1 } }");
        CompiledFunction badIndex = new CompiledFunction("answer", 0, 0, 0, 1, false, false,
                new int[]{Opcode.CALL_API.ordinal(), 99, 0, Opcode.RETURN.ordinal()}, new int[0]);
        CompiledFunction badArguments = new CompiledFunction("answer", 0, 0, 0, 1, false, false,
                new int[]{Opcode.CALL_API.ordinal(), 0, 0, Opcode.RETURN.ordinal()}, new int[0]);
        CompiledFunction badMode = new CompiledFunction("answer", 0, 0, 0, 1, false, false,
                new int[]{Opcode.TRUE.ordinal(), Opcode.CALL_SUSPEND.ordinal(), 0, 1, Opcode.RETURN.ordinal()}, new int[0]);
        assertTrue(new BytecodeVerifier(api).verify(withFunction(base, badIndex)).stream().anyMatch(d -> d.message().contains("out of range")));
        assertTrue(new BytecodeVerifier(api).verify(withFunction(base, badArguments)).stream().anyMatch(d -> d.message().contains("Argument count mismatch")));
        assertTrue(new BytecodeVerifier(api).verify(withFunction(base, badMode)).stream().anyMatch(d -> d.message().contains("call mode mismatch")));
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
    void setsRejectMutableNonHashableElementTypes() {
        List<Diagnostic> constructor = semanticDiagnostics("@Script(\"T\")\nscript T { values = set<List<int>>() int answer() { return 0 } }");
        assertTrue(constructor.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
        List<Diagnostic> declared = semanticDiagnostics("@Script(\"T\")\nscript T { int answer(Set<List<int>> values) { return 0 } }");
        assertTrue(declared.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
        @VeloraNamespace("badset")
        class BadSetBinding { @VeloraFunction(name = "values") public Set<List<Integer>> values() { return Set.of(); } }
        assertThrows(BindingValidationException.class, () -> api.registerAnnotated(new BadSetBinding()));
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
    void unannotatedBindingParameterNamesAreStableAcrossObfuscation() {
        @VeloraNamespace("stable")
        class Binding {
            @VeloraFunction(name = "sum") public int sum(int left, int right) { return left + right; }
        }
        api.registerAnnotated(new Binding());
        var descriptor = api.find("stable", "sum");
        assertEquals("arg0", descriptor.parameters().get(0).name());
        assertEquals("arg1", descriptor.parameters().get(1).name());
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
    void nonFiniteSettingValuesReturnValidationErrors() {
        SettingDescriptor range = new SettingDescriptor("value", "Value", VeloraTypes.DOUBLE, 0.0, null, null, null, 0, false, false, false, null,
                List.of(SettingDescriptor.Constraint.range(-10.0, 10.0)), 0);
        SettingDescriptor step = new SettingDescriptor("step", "Step", VeloraTypes.DOUBLE, 0.0, null, null, null, 0, false, false, false, null,
                List.of(SettingDescriptor.Constraint.step(0.5)), 0);
        assertFalse(io.velora.internal.setting.SettingValidator.validate(range, SettingValue.ofDouble(Double.NaN)).isValid());
        assertFalse(io.velora.internal.setting.SettingValidator.validate(range, SettingValue.ofDouble(Double.POSITIVE_INFINITY)).isValid());
        assertFalse(io.velora.internal.setting.SettingValidator.validate(step, SettingValue.ofDouble(Double.NEGATIVE_INFINITY)).isValid());
    }

    @Test
    void nullableTargetsPreserveNormalWideningRules() {
        assertTrue(VeloraTypes.isCompatible(VeloraTypes.INT, VeloraTypes.LONG.nullable()));
        assertTrue(VeloraTypes.isCompatible(VeloraTypes.INT.nullable(), VeloraTypes.LONG.nullable()));
        assertFalse(VeloraTypes.isCompatible(VeloraTypes.INT.nullable(), VeloraTypes.LONG));
        var handle = new io.velora.api.type.HandleType("PlayerRef", Object.class);
        assertTrue(VeloraTypes.isCompatible(handle, handle.nullable()));
        assertTrue(VeloraTypes.isCompatible(handle.nullable(), handle.nullable()));
        var otherHandle = new io.velora.api.type.HandleType("PlayerRef", String.class);
        assertFalse(VeloraTypes.isCompatible(handle, otherHandle));
        assertFalse(VeloraTypes.isCompatible(VeloraTypes.list(handle), VeloraTypes.list(otherHandle)));
        SettingDescriptor descriptor = new SettingDescriptor("value", "Value", VeloraTypes.LONG.nullable(), null, null, null, null, 0, false, false, false, null, List.of(), 0);
        SettingStore store = new SettingStore(List.of(descriptor));
        store.set("value", SettingValue.ofInt(42));
        assertEquals(42L, ((Number) store.get("value").value()).longValue());
    }

    @Test
    void settingStoreRejectsForgedNumericCarriersAndNormalizesDurationPersistence() {
        SettingDescriptor integer = new SettingDescriptor("amount", "Amount", VeloraTypes.INT, 1, null, null, null, 0, false, false, false, null, List.of(), 0);
        SettingDescriptor duration = new SettingDescriptor("delay", "Delay", VeloraTypes.DURATION, Duration.ofSeconds(1), null, null, null, 0, false, false, false, null, List.of(), 1);
        SettingStore store = new SettingStore(List.of(integer, duration));
        assertThrows(IllegalArgumentException.class, () -> store.set("amount", SettingValue.of(VeloraTypes.INT, Long.MAX_VALUE)));
        assertEquals(Duration.ofSeconds(1).toNanos(), ((Number) store.get("delay").value()).longValue());
        Map<String, SettingValue> snapshot = store.snapshot();
        assertEquals(snapshot, io.velora.internal.persistence.SettingsFileCodec.decode(io.velora.internal.persistence.SettingsFileCodec.encode(snapshot)));
        store.set("delay", SettingValue.of(VeloraTypes.DURATION, Duration.ofMillis(250)));
        assertEquals(Duration.ofMillis(250).toNanos(), ((Number) store.get("delay").value()).longValue());
    }

    @Test
    void invalidSettingConstraintMetadataIsRejectedExplicitly() {
        List<Diagnostic> negativeStep = semanticDiagnostics("@Script(\"BadStep\")\n@Version(\"1\")\nscript BadStep { @Setting(\"Speed\", step=-1) speed = 2 }");
        assertTrue(negativeStep.stream().anyMatch(d -> d.code() == DiagnosticCode.SETTING_OUT_OF_RANGE));
        List<Diagnostic> lengths = semanticDiagnostics("@Script(\"BadLength\")\n@Version(\"1\")\nscript BadLength { @Setting(\"Name\", minLength=5, maxLength=2) name = \"abc\" }");
        assertTrue(lengths.stream().anyMatch(d -> d.code() == DiagnosticCode.SETTING_OUT_OF_RANGE));
        SettingDescriptor descriptor = new SettingDescriptor("speed", "Speed", VeloraTypes.INT, 1, null, null, null, 0, false, false, false, null, List.of(SettingDescriptor.Constraint.step(-1)), 0);
        assertThrows(IllegalArgumentException.class, () -> new SettingStore(List.of(descriptor)));
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
    void pendingHostTasksResumeAsyncFunctions() {
        io.velora.api.task.VeloraTaskSource<Integer> source = io.velora.api.task.TaskFactory.create();
        api.namespace("asyncSuccess", ns -> ns.suspendFunction("value", VeloraTypes.INT, p -> {}, ctx -> source.task()));
        api.freeze();
        CompiledModule module = compile("@Script(\"AsyncSuccess\")\n@Version(\"1\")\nscript AsyncSuccess { async int answer() { return asyncSuccess.value() } }");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), api, new RuntimeErrorStore(10));
        ScriptFiber fiber = scheduler.spawnFiber("AsyncSuccess", module.functionByName("answer").index(), new ScriptValue[0]);
        scheduler.tick(System.nanoTime(), Map.of("AsyncSuccess", module), Map.of("AsyncSuccess", List.of()));
        assertEquals(FiberState.WAITING_TASK, fiber.state());
        source.succeed(42);
        scheduler.tick(System.nanoTime(), Map.of("AsyncSuccess", module), Map.of("AsyncSuccess", List.of()));
        assertEquals(FiberState.COMPLETED, fiber.state());
        assertEquals(42, ((Number) fiber.result().boxed()).intValue());
    }

    @Test
    void failedAsyncTasksAreRecordedAsRuntimeErrors() {
        io.velora.api.task.VeloraTaskSource<Integer> source = io.velora.api.task.TaskFactory.create();
        api.namespace("asyncFailure", ns -> ns.suspendFunction("value", VeloraTypes.INT, p -> {}, ctx -> source.task()));
        api.freeze();
        CompiledModule module = compile("@Script(\"AsyncFailure\")\n@Version(\"1\")\nscript AsyncFailure { async int answer() { return asyncFailure.value() } }");
        RuntimeErrorStore errors = new RuntimeErrorStore(10);
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), api, errors);
        ScriptFiber fiber = scheduler.spawnFiber("AsyncFailure", module.functionByName("answer").index(), new ScriptValue[0]);
        assertNotNull(fiber);
        scheduler.tick(System.nanoTime(), Map.of("AsyncFailure", module), Map.of("AsyncFailure", List.of()));
        assertFalse(fiber.isDone());
        source.fail(new IllegalStateException("async-boom"));
        scheduler.tick(System.nanoTime(), Map.of("AsyncFailure", module), Map.of("AsyncFailure", List.of()));
        assertTrue(fiber.isDone());
        assertEquals(1, errors.get("AsyncFailure").size());
        assertEquals("async-boom", errors.get("AsyncFailure").getFirst().message());
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
    void multiFileDiagnosticsKeepHelperSourcePath() {
        CompileResult result = compiler().compile(CompileRequest.builder("Multi")
                .source("main.vls", "@Script(\"Multi\")\n@Version(\"1\")\nscript Multi { int answer() { return helper() } }")
                .source("lib/helper.vls", "int helper() { return \"wrong\" }")
                .build());
        assertFalse(result.success());
        Diagnostic diagnostic = result.diagnostics().stream().filter(Diagnostic::isError).findFirst().orElseThrow();
        assertEquals("lib/helper.vls", diagnostic.range().filePath());
        assertEquals(1, diagnostic.range().startLine());
    }

    @Test
    void multiFileDiagnosticsDoNotOverlapAtTrailingNewlines() {
        CompileResult result = compiler().compile(CompileRequest.builder("Multi")
                .source("main.vls", "@Script(\"Multi\")\n@Version(\"1\")\nscript Multi { int answer() { return first() } } // } tail")
                .source("a.vls", "int first() { return second() }\n")
                .source("b.vls", "int second() { return \"wrong\" }\n")
                .build());
        assertFalse(result.success());
        Diagnostic diagnostic = result.diagnostics().stream().filter(Diagnostic::isError).findFirst().orElseThrow();
        assertEquals("b.vls", diagnostic.range().filePath());
        assertEquals(1, diagnostic.range().startLine());
    }

    @Test
    void localFileSystemNeverListsSymbolicLinkSources() throws Exception {
        Path root = Files.createTempDirectory("velora-fs-");
        Path external = Files.createTempFile("velora-external-", ".vls");
        try {
            LocalVeloraFileSystem fs = new LocalVeloraFileSystem(root);
            fs.writeAtomic("safe", "main.vls", "@Script(\"Safe\") script Safe {}", null);
            Path link = root.resolve("safe/link.vls");
            try {
                Files.createSymbolicLink(link, external);
            } catch (UnsupportedOperationException | java.io.IOException | SecurityException ignored) {
                return;
            }
            assertEquals(1, fs.listScripts().size());
            assertEquals("main.vls", fs.listScripts().getFirst().relativePath());
            assertThrows(IllegalArgumentException.class, () -> fs.readSource("safe", "link.vls"));
        } finally {
            if (Files.exists(root.resolve("safe/link.vls"))) Files.delete(root.resolve("safe/link.vls"));
            if (Files.exists(root.resolve("safe/main.vls"))) Files.delete(root.resolve("safe/main.vls"));
            if (Files.exists(root.resolve("safe"))) Files.delete(root.resolve("safe"));
            Files.deleteIfExists(root);
            Files.deleteIfExists(external);
        }
    }

    @Test
    void bytecodeDeserializerHandlesCorruptedInputsWithoutThrowing() {
        CompileResult result = compiler().compile(CompileRequest.builder("Fuzz")
                .source("main.vls", "@Script(\"Fuzz\")\n@Version(\"1\")\nscript Fuzz { int answer() { return 42 } }")
                .build());
        assertTrue(result.success());
        Random random = new Random(0x56454c4f5241L);
        byte[] valid = result.bytecode();
        for (int i = 0; i < 1_000; i++) {
            byte[] damaged;
            int mode = i % 3;
            if (mode == 0) {
                damaged = Arrays.copyOf(valid, random.nextInt(valid.length + 1));
            } else if (mode == 1) {
                damaged = valid.clone();
                for (int j = 0, changes = 1 + random.nextInt(8); j < changes; j++) damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
            } else {
                damaged = Arrays.copyOf(valid, valid.length + 1 + random.nextInt(32));
                for (int j = valid.length; j < damaged.length; j++) damaged[j] = (byte) random.nextInt(256);
            }
            assertDoesNotThrow(() -> DefaultScriptCompiler.deserializeBytecode(damaged, List.of()));
        }
    }

    @Test
    void bytecodeDeserializerRejectsTrailingGarbage() {
        CompileResult result = compiler().compile(CompileRequest.builder("Bytecode")
                .source("main.vls", "@Script(\"Bytecode\")\n@Version(\"1\")\nscript Bytecode { int answer() { return 42 } }")
                .build());
        assertTrue(result.success());
        byte[] damaged = Arrays.copyOf(result.bytecode(), result.bytecode().length + 1);
        damaged[damaged.length - 1] = 1;
        assertNull(DefaultScriptCompiler.deserializeBytecode(damaged, List.of()));
    }

    @Test
    void bytecodeVerifierRejectsInvalidModuleMetadata() {
        CompiledModule base = compile("@Script(\"MetaCheck\")\n@Version(\"1\")\nscript MetaCheck { int answer() { return 42 } }");
        CompiledModule malformed = new CompiledModule(base.scriptId(), base.scriptName(), base.version(), base.languageVersion(), base.sourceHash(), base.registryHash(),
                base.constantPool(), base.functions(), base.settings(), List.of("state"), List.of(), List.of(0), List.of(false), base.lifecycleHooks(),
                List.of(new CompiledModule.EventHandlerInfo("client.tick", "answer", 999, false)), base.fieldInitializers(), base.author(), base.description());
        List<Diagnostic> diagnostics = new BytecodeVerifier(api).verify(malformed);
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Persistent field metadata sizes")));
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("function index out of range")));
    }

    @Test
    void bytecodeVerifierRejectsInvalidSourceLineMetadata() {
        CompiledModule base = compile("@Script(\"LineMeta\")\n@Version(\"1\")\nscript LineMeta { int answer() { return 42 } }");
        CompiledFunction original = base.functions().getFirst();
        CompiledFunction malformedFunction = new CompiledFunction(original.name(), original.index(), original.parameterCount(), original.localCount(), original.maxStack(), original.suspending(), original.isLifecycle(), original.code(), new int[original.code().length + 1]);
        List<CompiledFunction> functions = new ArrayList<>(base.functions());
        functions.set(0, malformedFunction);
        CompiledModule malformed = new CompiledModule(base.scriptId(), base.scriptName(), base.version(), base.languageVersion(), base.sourceHash(), base.registryHash(),
                base.constantPool(), functions, base.settings(), base.persistentFieldIds(), base.persistentFieldTypes(), base.persistentFieldIndices(), base.persistentFieldIsStatic(),
                base.lifecycleHooks(), base.eventHandlers(), base.fieldInitializers(), base.author(), base.description());
        assertTrue(new BytecodeVerifier(api).verify(malformed).stream().anyMatch(diagnostic -> diagnostic.message().contains("Invalid function metadata")));
    }

    @Test
    void runtimeErrorsKeepSourceLine() {
        CompiledModule module = compile("@Script(\"LineT\")\n@Version(\"1\")\nscript LineT {\n    int answer() {\n        int zero = 0\n        return 1 / zero\n    }\n}");
        VmExecutionResult result = execute(module);
        assertFalse(result.success());
        assertTrue(result.error().line() >= 5, String.valueOf(result.error()));
    }

    @Test
    void languageServiceUsesSemanticSymbolsAndLexicalCallDelimiters() {
        api.namespace("client", ns -> ns.function("mix", VeloraTypes.INT,
                p -> { p.required("a", VeloraTypes.STRING); p.required("b", VeloraTypes.INT); p.required("c", VeloraTypes.INT); }, ctx -> 0));
        var language = new io.velora.internal.language.DefaultLanguageService(api, types, null, settings, constants);
        var editor = language.openEditor("SemanticEditor", "main.vls");
        String source = "@Script(\"SemanticEditor\")\n@Version(\"1\")\nscript SemanticEditor { int value = 1\n int helper(int amount) { return amount + value }\n int answer() { return client.mix(\"x,(y)\", 2, 3) } }";
        editor.updateText(source);
        assertFalse(editor.snapshot().hasErrors(), editor.snapshot().diagnostics().toString());
        assertTrue(editor.completions(5, source.lines().toList().get(4).indexOf("client") + 1).stream().anyMatch(item -> item.label().equals("value")));
        assertTrue(editor.hover(3, source.lines().toList().get(2).indexOf("value") + 1).orElseThrow().content().contains("Property `Int`"));
        int thirdArgument = source.lines().toList().get(4).lastIndexOf("3)") + 1;
        assertEquals(2, editor.signatureHelp(5, thirdArgument).orElseThrow().activeParameter());
        editor.updateText("@Script(\"D\")\nscript D { int helper(int amount) { return amount }\n int answer() { for (item in list<int>()) { return item } return helper(1) } }");
        assertEquals(2, editor.definition(2, 44).orElseThrow().line());
        assertTrue(editor.rename("amount", "if").isEmpty());
        language.close();
    }

    @Test
    void definitionRespectsRealScopesTypedForBindersAndForwardMembers() {
        DefaultEditorSession editor = new DefaultEditorSession("D", "main.vls");
        String source = "@Script(\"D\")\nscript D {\n    int answer() { return helper() + value }\n    int helper() { return 40 }\n    int value = 2\n    int loop() {\n        for (int item in list<int>()) {\n            return item\n        }\n        return 0\n    }\n}";
        editor.updateText(source);
        List<String> lines = source.lines().toList();
        int helperUse = lines.get(2).indexOf("helper") + 1;
        int valueUse = lines.get(2).indexOf("value") + 1;
        assertEquals(4, editor.definition(3, helperUse).orElseThrow().line());
        assertEquals(5, editor.definition(3, valueUse).orElseThrow().line());
        int itemUse = lines.get(7).indexOf("item") + 1;
        var itemDefinition = editor.definition(8, itemUse).orElseThrow();
        assertEquals(7, itemDefinition.line());
        assertEquals(lines.get(6).indexOf("item") + 1, itemDefinition.column());

        String sibling = "@Script(\"S\")\nscript S {\n    int first(int amount) { return amount }\n    int second() { if (true) { int local = 1 } if (true) { return local + amount } return 0 }\n}";
        editor.updateText(sibling);
        List<String> siblingLines = sibling.lines().toList();
        assertTrue(editor.definition(4, siblingLines.get(3).indexOf("local +") + 1).isEmpty());
        assertTrue(editor.definition(4, siblingLines.get(3).lastIndexOf("amount") + 1).isEmpty());
        editor.close();
    }

    @Test
    void definitionDoesNotTreatAssignmentsAsDeclarations() {
        DefaultEditorSession editor = new DefaultEditorSession("Assign", "main.vls");
        String source = "@Script(\"Assign\")\nscript Assign {\n    int answer() {\n        int value = 1\n        value = 2\n        return value\n    }\n}";
        editor.updateText(source);
        List<String> lines = source.lines().toList();
        int use = lines.get(5).indexOf("value") + 1;
        var definition = editor.definition(6, use).orElseThrow();
        assertEquals(4, definition.line());
        assertEquals(lines.get(3).indexOf("value") + 1, definition.column());
        assertEquals(3, editor.rename("value", "renamed").size());
        editor.close();
    }

    @Test
    void renameRefusesAmbiguousShadowedSymbolsInsteadOfEditingUnrelatedScopes() {
        DefaultEditorSession editor = new DefaultEditorSession("Rename", "main.vls");
        String source = "@Script(\"Rename\")\nscript Rename {\n    int first(int value) { return value }\n    int second(int value) { return value }\n}";
        editor.updateText(source);
        assertTrue(editor.rename("value", "renamed").isEmpty());

        String unique = "@Script(\"Rename\")\nscript Rename {\n    int field = 1\n    int answer() { return field }\n}";
        editor.updateText(unique);
        assertEquals(2, editor.rename("field", "renamed").size());
        editor.close();
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
    void functionContextNeverSilentlyNarrowsNumericArguments() {
        @VeloraNamespace("narrow")
        class Binding {
            @VeloraFunction(name = "shortValue") public int shortValue(short value) { return value; }
        }
        api.registerAnnotated(new Binding());
        CompiledModule valid = compile("@Script(\"NarrowValid\")\n@Version(\"1\")\nscript NarrowValid { int answer() { return narrow.shortValue(32000) } }");
        VmExecutionResult validResult = execute(valid);
        assertTrue(validResult.success(), String.valueOf(validResult.error()));
        assertEquals(32000, ((Number) validResult.returnValue().boxed()).intValue());
        CompiledModule overflow = compile("@Script(\"NarrowOverflow\")\n@Version(\"1\")\nscript NarrowOverflow { int answer() { return narrow.shortValue(40000) } }");
        VmExecutionResult overflowResult = execute(overflow);
        assertFalse(overflowResult.success());
        assertEquals(DiagnosticCode.RUNTIME_API_ERROR, overflowResult.error().code());
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
    void freezingAnEngineTwiceDoesNotRegisterExtensionsTwice() {
        VeloraEngine engine = Velora.builder().host(host(new ArrayList<>())).build();
        int[] registrations = {0};
        engine.extensions().register(new io.velora.api.VeloraExtension() {
            @Override public String id() { return "once"; }
            @Override public String version() { return "1"; }
            @Override public void register(io.velora.api.VeloraExtensionContext context) {
                registrations[0]++;
                context.api().namespace("once", ns -> ns.function("value", VeloraTypes.INT, ctx -> 1));
            }
        });
        engine.freeze();
        engine.freeze();
        assertEquals(1, registrations[0]);
        assertEquals(io.velora.api.VeloraState.FROZEN, engine.state());
        engine.close();
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
    void extensionFreezeRollsBackTheWholeBatchAndCanRetry() {
        VeloraEngine engine = Velora.builder().host(host(new ArrayList<>())).build();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        engine.extensions().register(new io.velora.api.VeloraExtension() {
            @Override public String id() { return "first"; }
            @Override public String version() { return "1"; }
            @Override public void register(io.velora.api.VeloraExtensionContext context) {
                context.api().namespace("firstExtension", ns -> ns.function("value", VeloraTypes.INT, ctx -> 1));
            }
        });
        engine.extensions().register(new io.velora.api.VeloraExtension() {
            @Override public String id() { return "second"; }
            @Override public String version() { return "1"; }
            @Override public void register(io.velora.api.VeloraExtensionContext context) {
                if (fail.getAndSet(false)) throw new IllegalStateException("first attempt fails");
                context.api().namespace("secondExtension", ns -> ns.function("value", VeloraTypes.INT, ctx -> 2));
            }
        });

        assertThrows(IllegalStateException.class, engine::freeze);
        assertNull(engine.api().find("firstExtension", "value"));
        assertNull(engine.api().find("secondExtension", "value"));
        engine.freeze();
        assertNotNull(engine.api().find("firstExtension", "value"));
        assertNotNull(engine.api().find("secondExtension", "value"));
        engine.close();
    }

    @Test
    void enumSettingsCompileAndPersistThroughDescriptorAwareCodec() {
        var modeType = types.enumType("Mode", Mode.class, List.of(
                new io.velora.api.type.EnumType.Constant("FAST", Mode.FAST),
                new io.velora.api.type.EnumType.Constant("SLOW", Mode.SLOW)));
        CompiledModule module = compile("@Script(\"EnumSetting\")\nscript EnumSetting { @Setting(\"Mode\") Mode mode = Mode.FAST int answer() { return 1 } }");
        SettingDescriptor descriptor = module.settings().getFirst();
        assertEquals(modeType.name(), descriptor.type().name());
        assertEquals(Mode.FAST, descriptor.defaultValue());
        SettingStore store = new SettingStore(module.settings());
        store.set("mode", SettingValue.of(modeType, Mode.SLOW));
        String encoded = io.velora.internal.persistence.SettingsFileCodec.encode(store.snapshot());
        Map<String, SettingValue> decoded = io.velora.internal.persistence.SettingsFileCodec.decode(encoded, module.settings());
        SettingStore restored = new SettingStore(module.settings());
        assertEquals(1, restored.applySnapshot(decoded));
        assertEquals(Mode.SLOW, restored.get("mode").value());
    }

    @Test
    void settingsRejectTypesThatCannotBePersisted() {
        types.handle("Player", Object.class);
        List<Diagnostic> diagnostics = semanticDiagnostics("@Script(\"UnsupportedSetting\")\nscript UnsupportedSetting { @Setting(\"Target\") Player? target = null }");
        assertTrue(diagnostics.stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_INVALID_PERSISTENT_TYPE));
    }

    @Test
    void customSettingKindsValidateTheirSchemaAndDriveTypeInference() {
        settings.register(io.velora.api.setting.SettingKind.named("Scaled")
                .identifierParameter()
                .positional("name", io.velora.api.setting.SettingKind.Parameter.ParameterRole.DISPLAY_NAME, VeloraTypes.STRING, true)
                .positional("scale", io.velora.api.setting.SettingKind.Parameter.ParameterRole.NAMED, VeloraTypes.DOUBLE, true)
                .positional("defaultValue", io.velora.api.setting.SettingKind.Parameter.ParameterRole.DEFAULT_VALUE, VeloraTypes.DOUBLE, true)
                .resultType(VeloraTypes.DOUBLE)
                .editor("scaled")
                .build());
        DefaultScriptCompiler compiler = compiler();
        CompileRequest goodRequest = CompileRequest.builder("scaled")
                .source("main.vls", "@Script(\"Scaled\")\nscript Scaled { @Setting(\"Value\", kind=\"Scaled\", scale=2.0) value = 1 }")
                .build();
        CompileResult good = compiler.compile(goodRequest);
        assertTrue(good.success(), good.diagnostics().toString());
        SettingDescriptor descriptor = compiler.compileToModule(goodRequest).settings().getFirst();
        assertEquals(VeloraTypes.DOUBLE, descriptor.type());
        assertEquals("scaled", descriptor.editor().orElseThrow().editorId());

        CompileResult missing = compiler.compile(CompileRequest.builder("missing")
                .source("main.vls", "@Script(\"Missing\")\nscript Missing { @Setting(\"Value\", kind=\"Scaled\") value = 1 }")
                .build());
        assertFalse(missing.success());
        assertTrue(missing.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_INVALID_ARGUMENT));

        CompileResult wrong = compiler.compile(CompileRequest.builder("wrong")
                .source("main.vls", "@Script(\"Wrong\")\nscript Wrong { @Setting(\"Value\", kind=\"Scaled\", scale=\"bad\") value = 1 }")
                .build());
        assertFalse(wrong.success());
        assertTrue(wrong.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_WRONG_ARG_TYPE));
    }

    @Test
    void registriesAndTypeBuildersRejectAmbiguousDuplicateEntries() {
        constants.register("Answers", "VALUE", VeloraTypes.INT, 1);
        assertThrows(IllegalStateException.class, () -> constants.register("Answers", "VALUE", VeloraTypes.INT, 2));
        assertThrows(IllegalArgumentException.class, () -> constants.register("Answers", "BAD", VeloraTypes.INT, "not-an-int"));
        assertNull(constants.find("Answers", "BAD"));
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

    @Test
    void enabledScriptReloadReplacesStaleInMemoryState() throws Exception {
        Path root = Files.createTempDirectory("velora-enabled-store-");
        VeloraFileSystem fileSystem = VeloraFileSystem.local(root);
        fileSystem.writeDataAtomic("", "enabled.velora", "alpha\nbeta".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        EnabledScriptsStore store = new EnabledScriptsStore(fileSystem);
        assertTrue(store.load());
        assertEquals(Set.of("alpha", "beta"), store.enabledScripts());
        fileSystem.writeDataAtomic("", "enabled.velora", "beta".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(store.load());
        assertEquals(Set.of("beta"), store.enabledScripts());
        fileSystem.writeDataAtomic("", "enabled.velora", new byte[0]);
        assertTrue(store.load());
        assertTrue(store.enabledScripts().isEmpty());
    }

    @Test
    void enabledScriptStoreReportsPersistenceFailuresWithoutLosingRuntimeState() {
        VeloraFileSystem fileSystem = new VeloraFileSystem() {
            @Override public List<io.velora.host.ScriptFileEntry> listScripts() { return List.of(); }
            @Override public io.velora.host.SourceSnapshot readSource(String scriptId, String relativePath) { return null; }
            @Override public io.velora.host.FileRevision writeAtomic(String scriptId, String relativePath, String content, io.velora.host.FileRevision expectedRevision) { throw new UnsupportedOperationException(); }
            @Override public io.velora.host.FileTransaction beginTransaction(String scriptId) { throw new UnsupportedOperationException(); }
            @Override public byte[] readData(String scriptId, String key) { throw new IllegalStateException("read failed"); }
            @Override public void writeDataAtomic(String scriptId, String key, byte[] data) { throw new IllegalStateException("write failed"); }
            @Override public boolean scriptExists(String scriptId) { return false; }
            @Override public void deleteScript(String scriptId) { }
        };
        EnabledScriptsStore store = new EnabledScriptsStore(fileSystem);
        assertFalse(store.load());
        assertFalse(store.enable("alpha"));
        assertTrue(store.isEnabled("alpha"));
        assertFalse(store.disable("alpha"));
        assertFalse(store.isEnabled("alpha"));
    }

    @Test
    void bytecodeCacheRemainsConsistentUnderConcurrentAccess() throws Exception {
        BytecodeCache cache = new BytecodeCache();
        CompiledModule module = compile("@Script(\"CacheConcurrency\")\n@Version(\"1\")\nscript CacheConcurrency { int answer() { return 42 } }");
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int thread = t;
            threads.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 1000; i++) {
                    String scriptId = "script-" + (i % 4);
                    String hash = "hash-" + thread + '-' + i;
                    cache.put(scriptId, hash, "registry", module);
                    cache.get(scriptId, hash, "registry");
                    if ((i & 7) == 0) cache.invalidate("script-" + ((i + 1) % 4));
                }
            }));
        }
        for (Thread thread : threads) thread.join();
        cache.clear();
        assertNull(cache.get("script-0", "hash", "registry"));
    }

    @Test
    void lexerAndParserHandleArbitraryEditorText() {
        Random random = new Random(0x56454C4F5241L);
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_@{}()[]<>,.:;!?+\\-*/%=\"' \t\r\n";
        for (int sample = 0; sample < 2000; sample++) {
            int length = random.nextInt(129);
            StringBuilder source = new StringBuilder(length);
            for (int i = 0; i < length; i++) source.append(alphabet.charAt(random.nextInt(alphabet.length())));
            new Lexer(source.toString(), "fuzz.vls").lex();
            Parser.parse(source.toString(), "fuzz.vls");
        }
    }

    @Test
    void hostApiResultsCannotViolateDeclaredVeloraTypes() {
        api.namespace("badResult", ns -> {
            ns.function("textAsInt", VeloraTypes.INT, ctx -> "wrong");
            ns.function("longAsInt", VeloraTypes.INT, ctx -> 4_294_967_297L);
            ns.function("scriptValueAsInt", VeloraTypes.INT, ctx -> new StringValue("wrong"));
            ns.function("badVec2", VeloraTypes.VEC2, ctx -> new double[]{1.0});
            ns.function("badColor", VeloraTypes.COLOR, ctx -> new int[]{255, 255, 255});
        });
        api.freeze();
        for (String function : List.of("textAsInt", "longAsInt", "scriptValueAsInt")) {
            CompiledModule module = compile("@Script(\"BadResult\")\nscript BadResult { int answer() { return badResult." + function + "() } }");
            VmExecutionResult result = new VirtualMachine(api, List.of(), 100_000).execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
            assertFalse(result.success());
            assertEquals(DiagnosticCode.RUNTIME_API_ERROR, result.error().code());
        }
        for (String function : List.of("badVec2", "badColor")) {
            CompiledModule module = compile("@Script(\"BadShape\")\nscript BadShape { int answer() { badResult." + function + "()\n return 1 } }");
            VmExecutionResult result = new VirtualMachine(api, List.of(), 100_000).execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
            assertFalse(result.success());
            assertEquals(DiagnosticCode.RUNTIME_API_ERROR, result.error().code());
        }
    }

    @Test
    void mainThreadApisCannotRunOffMainThread() {
        boolean[] invoked = {false};
        api.namespace("client", ns -> ns.function("touch", VeloraTypes.UNIT, ctx -> {
            invoked[0] = true;
            return null;
        }).thread(ScriptThread.MAIN));
        api.freeze();
        CompiledModule module = compile("@Script(\"MainThread\")\nscript MainThread { run() { client.touch() } }");
        RuntimeErrorStore errors = new RuntimeErrorStore(10);
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), api, errors, null, constants, types, System::nanoTime, () -> false);
        ScriptFiber fiber = scheduler.spawnFiber(module.scriptId(), module.functionByName("run").index(), new ScriptValue[0]);
        scheduler.tick(System.nanoTime(), Map.of(module.scriptId(), module), Map.of(module.scriptId(), List.of()));
        assertEquals(FiberState.FAILED, fiber.state());
        assertFalse(invoked[0]);
        assertTrue(errors.get(module.scriptId()).getFirst().message().contains("main thread"));
    }

    @Test
    void taskFactoryIsolatesCompletionAndCancellationCallbacks() {
        var completion = io.velora.api.task.TaskFactory.<Integer>create();
        int[] completed = {0};
        completion.task().onComplete(task -> { throw new IllegalStateException("listener"); });
        completion.task().onComplete(task -> completed[0]++);
        assertTrue(completion.succeed(42));
        assertEquals(1, completed[0]);
        assertEquals(42, completion.task().result());

        var cancellation = io.velora.api.task.TaskFactory.<Integer>create();
        int[] cancelled = {0};
        cancellation.onCancel(() -> { throw new IllegalStateException("cancel"); });
        cancellation.onCancel(() -> cancelled[0]++);
        assertTrue(cancellation.cancel());
        assertEquals(1, cancelled[0]);
    }

    @Test
    void cancellationSourceRunsCallbacksOutsideItsMonitorAndUnlinksChildren() throws Exception {
        var parent = io.velora.api.task.CancellationSource.create();
        var child = io.velora.api.task.CancellationSource.childOf(parent);
        boolean[] held = {true};
        child.onCancel(() -> held[0] = Thread.holdsLock(child));
        assertTrue(child.cancel());
        assertFalse(held[0]);
        assertFalse(child.cancel());
        var callbacks = io.velora.api.task.CancellationSource.class.getDeclaredField("callbacks");
        callbacks.setAccessible(true);
        assertTrue(((List<?>) callbacks.get(parent)).isEmpty());
    }

    @Test
    void taskListenerRegistrationFailureDoesNotLeakSchedulerState() {
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), api);
        ScriptFiber fiber = scheduler.spawnFiber("task-listener", 0, new ScriptValue[0]);
        assertNotNull(fiber);
        io.velora.api.task.VeloraTask<Object> task = new io.velora.api.task.VeloraTask<>() {
            @Override public io.velora.api.task.TaskState state() { return io.velora.api.task.TaskState.PENDING; }
            @Override public Object result() { throw new IllegalStateException(); }
            @Override public Throwable failure() { throw new IllegalStateException(); }
            @Override public boolean cancel() { return true; }
            @Override public void onComplete(io.velora.api.task.TaskListener<Object> listener) { throw new IllegalStateException("listener rejected"); }
        };
        assertThrows(IllegalStateException.class, () -> scheduler.watchTask(fiber.id(), task, VeloraTypes.INT));
        assertEquals(0, scheduler.resources("task-listener").tasks());
        assertEquals(-1L, fiber.awaitTaskId());
    }

    @Test
    void settingsUseDeclaredTypeAtRuntime() {
        class PlayerRef {}
        var playerType = new io.velora.api.type.HandleType("Player", PlayerRef.class);
        PlayerRef player = new PlayerRef();
        SettingDescriptor descriptor = new SettingDescriptor("target", "Target", playerType, player, null, null, null, 0, false, false, false, null, List.of(), 0);
        SettingStore store = new SettingStore(List.of(descriptor));
        int[] code = {Opcode.LOAD_SETTING.ordinal(), 0, Opcode.RETURN.ordinal()};
        CompiledFunction function = new CompiledFunction("answer", 0, 0, 0, 1, false, false, code, new int[code.length]);
        CompiledModule module = new CompiledModule("settings", "Settings", "1", 2, "source", "registry", new ConstantPool(), List.of(function), List.of(descriptor), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        VmExecutionResult result = new VirtualMachine(api, List.of(descriptor), store, 100).execute(module, 0, new ScriptValue[0]);
        assertTrue(result.success(), result.error() != null ? result.error().message() : "");
        assertTrue(result.returnValue() instanceof HandleValue);
        HandleValue handle = (HandleValue) result.returnValue();
        assertEquals("Player", handle.typeName());
        assertSame(player, handle.handle());
    }

    @Test
    void persistentCollectionMutationsRespectScriptMemoryBudget() {
        String payload = "x".repeat(100);
        CompiledModule module = compile("@Script(\"MemoryMutation\")\nscript MemoryMutation { values = list<String>() int fill() { values.add(\"" + payload + "\") return values.size } int size() { return values.size } }");
        VeloraLimits limits = VeloraLimits.builder().memoryPerScript(400).build();
        RuntimeErrorStore errors = new RuntimeErrorStore(10);
        ScriptScheduler scheduler = new ScriptScheduler(limits, api, errors);
        ScriptFiber fill = scheduler.spawnFiber(module.scriptId(), module.functionByName("fill").index(), new ScriptValue[0]);
        assertNotNull(fill);
        scheduler.tick(System.nanoTime(), Map.of(module.scriptId(), module), Map.of(module.scriptId(), List.of()));
        assertEquals(FiberState.FAILED, fill.state());
        assertEquals(DiagnosticCode.RUNTIME_RESOURCE_LIMIT.name(), errors.get(module.scriptId()).getFirst().errorType());
        ScriptFiber size = scheduler.spawnFiber(module.scriptId(), module.functionByName("size").index(), new ScriptValue[0]);
        assertNotNull(size);
        scheduler.tick(System.nanoTime(), Map.of(module.scriptId(), module), Map.of(module.scriptId(), List.of()));
        assertEquals(FiberState.COMPLETED, size.state());
        assertEquals(0, ((Number) size.result().boxed()).intValue());
    }

    @Test
    void apiCostAccountingCannotOverflowPastTheLimit() {
        api.namespace("expensive", ns -> ns.function("hit", VeloraTypes.UNIT, ctx -> null).cost(2_000_000_000));
        api.freeze();
        CompiledModule module = compile("@Script(\"CostOverflow\")\nscript CostOverflow { run() { expensive.hit() expensive.hit() } }");
        VeloraLimits limits = VeloraLimits.builder().apiCostPerScriptTick(Integer.MAX_VALUE).build();
        RuntimeErrorStore errors = new RuntimeErrorStore(10);
        ScriptScheduler scheduler = new ScriptScheduler(limits, api, errors);
        ScriptFiber fiber = scheduler.spawnFiber("CostOverflow", module.functionByName("run").index(), new ScriptValue[0]);
        scheduler.tick(System.nanoTime(), Map.of("CostOverflow", module), Map.of("CostOverflow", List.of()));
        assertEquals(FiberState.FAILED, fiber.state());
        assertEquals(2_000_000_000, scheduler.apiCost("CostOverflow"));
        assertEquals(DiagnosticCode.RUNTIME_RESOURCE_LIMIT.name(), errors.get("CostOverflow").getFirst().errorType());
    }

    @Test
    void sharedValueGraphsCannotExplodeMemoryEstimation() {
        ScriptValue value = PrimitiveValue.of(1);
        for (int depth = 0; depth < 7; depth++) value = new ListValue(Collections.nCopies(1000, value));
        VeloraLimits limits = VeloraLimits.builder().memoryPerScript(Long.MAX_VALUE).maxCollectionElements(1000).build();
        ScriptScheduler scheduler = new ScriptScheduler(limits, api);
        assertNull(scheduler.spawnFiber("graph", 0, new ScriptValue[]{value}));
    }

    @Test
    void cyclicHostValuesAreRejectedWithoutStackOverflow() {
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertThrows(IllegalArgumentException.class, () -> VirtualMachine.javaToValue(VeloraTypes.list(VeloraTypes.list(VeloraTypes.INT)), cyclic));
        assertThrows(IllegalArgumentException.class, () -> ScriptValue.fromJava(cyclic));

        ListValue scriptCycle = new ListValue(List.of());
        scriptCycle.elements().add(scriptCycle);
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), api);
        assertNull(scheduler.spawnFiber("cycle", 0, new ScriptValue[]{scriptCycle}));
    }

    @Test
    void runtimeTypeChecksValidateBuiltinValueShape() {
        ConstantPool uuidPool = new ConstantPool();
        int invalidUuid = uuidPool.addString("not-a-uuid");
        int uuidType = uuidPool.addString("UUID");
        CompiledFunction uuidFunction = new CompiledFunction("answer", 0, 0, 0, 1, false, false,
                new int[]{Opcode.CONST.ordinal(), invalidUuid, Opcode.IS_TYPE.ordinal(), uuidType, Opcode.RETURN.ordinal()}, new int[5]);
        CompiledModule uuidModule = new CompiledModule("type-uuid", "TypeUuid", "1", 2, "source", "registry", uuidPool, List.of(uuidFunction),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        VmExecutionResult uuidResult = new VirtualMachine(api, List.of(), 100).execute(uuidModule, 0, new ScriptValue[0]);
        assertTrue(uuidResult.success());
        assertEquals(false, uuidResult.returnValue().boxed());

        ConstantPool vecPool = new ConstantPool();
        int x = vecPool.addDouble(1);
        int y = vecPool.addDouble(2);
        int vec3Type = vecPool.addString("Vec3");
        CompiledFunction vecFunction = new CompiledFunction("answer", 0, 0, 0, 2, false, false,
                new int[]{Opcode.CONST.ordinal(), x, Opcode.CONST.ordinal(), y, Opcode.CREATE_LIST.ordinal(), 2, Opcode.IS_TYPE.ordinal(), vec3Type, Opcode.RETURN.ordinal()}, new int[9]);
        CompiledModule vecModule = new CompiledModule("type-vec", "TypeVec", "1", 2, "source", "registry", vecPool, List.of(vecFunction),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        VmExecutionResult vecResult = new VirtualMachine(api, List.of(), 100).execute(vecModule, 0, new ScriptValue[0]);
        assertTrue(vecResult.success());
        assertEquals(false, vecResult.returnValue().boxed());

        ConstantPool colorPool = new ConstantPool();
        int c = colorPool.addDouble(1);
        int colorType = colorPool.addString("Color");
        CompiledFunction colorFunction = new CompiledFunction("answer", 0, 0, 0, 4, false, false,
                new int[]{Opcode.CONST.ordinal(), c, Opcode.CONST.ordinal(), c, Opcode.CONST.ordinal(), c, Opcode.CONST.ordinal(), c,
                        Opcode.CREATE_LIST.ordinal(), 4, Opcode.IS_TYPE.ordinal(), colorType, Opcode.RETURN.ordinal()}, new int[13]);
        CompiledModule colorModule = new CompiledModule("type-color", "TypeColor", "1", 2, "source", "registry", colorPool, List.of(colorFunction),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        VmExecutionResult colorResult = new VirtualMachine(api, List.of(), 100).execute(colorModule, 0, new ScriptValue[0]);
        assertTrue(colorResult.success());
        assertEquals(false, colorResult.returnValue().boxed());
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
    @Test
    void compileResultDoesNotExposeMutableBytecode() {
        VeloraEngine engine = Velora.builder().host(host(new ArrayList<>())).build();
        engine.freeze();
        CompileResult result = engine.compiler().compile(CompileRequest.builder("immutable-bytecode")
                .source("main.vls", "@Script(\"Immutable\")\nscript Immutable { int answer() { return 42 } }")
                .build());
        assertTrue(result.success(), result.diagnostics().toString());
        byte[] first = result.bytecode();
        byte original = first[0];
        first[0] ^= 0x7f;
        assertEquals(original, result.bytecode()[0]);
        engine.close();
    }

    @Test
    void synchronousLifecycleHooksCannotSuspendButRunCan() {
        VeloraEngine engine = Velora.builder().host(host(new ArrayList<>())).build();
        engine.freeze();
        CompileResult invalid = engine.compiler().compile(CompileRequest.builder("lifecycle-suspend")
                .source("main.vls", "@Script(\"T\")\nscript T { @Enable async enable() { delay(1) } }")
                .build());
        assertFalse(invalid.success());
        assertTrue(invalid.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_ASYNC_VIOLATION));

        CompileResult run = engine.compiler().compile(CompileRequest.builder("run-suspend")
                .source("main.vls", "@Script(\"T\")\nscript T { @Run async run() { delay(1) } }")
                .build());
        assertTrue(run.success(), run.diagnostics().toString());
        engine.close();
    }

}
