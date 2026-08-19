package io.velora;

import io.velora.api.Velora;
import io.velora.api.VeloraEngine;
import io.velora.api.compiler.CompileRequest;
import io.velora.api.language.CompletionItem;
import io.velora.binding.annotation.VeloraFunction;
import io.velora.binding.annotation.VeloraImport;
import io.velora.binding.annotation.VeloraParam;
import io.velora.host.*;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.compiler.DefaultScriptCompiler;
import io.velora.internal.vm.ScriptValue;
import io.velora.internal.vm.VirtualMachine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StdlibV2Test {
    @VeloraImport(value = "client.util.DocUtil", description = "Example documented client utility")
    static final class DocUtil {
        @VeloraFunction(name = "join", description = "Joins a prefix with arbitrary values")
        public static String join(@VeloraParam(value = "prefix", description = "Prefix text") String prefix,
                                  @VeloraParam(value = "values", description = "Values to append") Object... values) {
            StringBuilder result = new StringBuilder(prefix);
            for (Object value : values) result.append(':').append(value);
            return result.toString();
        }
    }

    @Test
    void stdlibExecutesThroughCompilerAndVm() {
        TestHost host = new TestHost();
        VeloraEngine engine = Velora.builder().host(host).build();
        engine.freeze();
        String source = """
                @Script("Stdlib")
                script Stdlib {
                    String text() {
                        List<int> values = list<int>()
                        values.add(40)
                        values.add(2)
                        return string.valueOf(values.first() + values.last()) + ":" + " ABC ".trim().lower()
                    }
                    int converted() { return convert.int("42") }
                    double maths() { return math.clamp(-2, 0, 10) + math.PI }
                    int collections() {
                        List<int> values = [40, 2]
                        Map<String, int> scores = map<String, int>()
                        scores.put("answer", 42)
                        return values.indexOf(2) + values.removeAt(0) + scores.getOrDefault("answer", 0) + scores.getOrDefault("missing", 1)
                    }
                    String strings() { return "a,b,c".split(",").last().upper() }
                    char character() { return "abc".charAt(1) }
                    char convertedCharacter() { return convert.char("Z") }
                    int? safeParse() { return string.tryParseInt("nope") }
                    UUID? safeUuid() { return uuid.tryParse("invalid") }
                    String nullText() { return string.valueOf(null) }
                    String joined() { return string.join("-", [1, 2, 3]) }
                    double advancedMath() { return math.hypot(3, 4) + math.lerp(0, 10, 0.5) }
                    boolean randomBounds() { return random.int(7, 8) == 7 && !random.chance(0) && random.chance(1) }
                    counter = 0
                    String mark() { counter++ return "x" }
                    String? safeMethod() { String? value = null return value?.trim() }
                    int safeArguments() { String? value = null value?.contains(mark()) return counter }
                    run() {
                        List<int> values = [1, 2]
                        console.print("value", 42, true, values)
                    }
                }
                """;
        CompiledModule module = compile(engine, "stdlib", source);
        assertEquals("42:abc", execute(engine, module, "text").boxed());
        assertEquals(42, ((Number) execute(engine, module, "converted").boxed()).intValue());
        assertEquals(Math.PI, ((Number) execute(engine, module, "maths").boxed()).doubleValue(), 0.0000001);
        assertEquals(84, ((Number) execute(engine, module, "collections").boxed()).intValue());
        assertEquals("C", execute(engine, module, "strings").boxed());
        assertEquals('b', execute(engine, module, "character").boxed());
        assertEquals('Z', execute(engine, module, "convertedCharacter").boxed());
        assertNull(execute(engine, module, "safeParse").boxed());
        assertNull(execute(engine, module, "safeUuid").boxed());
        assertEquals("null", execute(engine, module, "nullText").boxed());
        assertEquals("1-2-3", execute(engine, module, "joined").boxed());
        assertEquals(10.0, ((Number) execute(engine, module, "advancedMath").boxed()).doubleValue(), 0.0000001);
        assertEquals(true, execute(engine, module, "randomBounds").boxed());
        assertNull(execute(engine, module, "safeMethod").boxed());
        assertEquals(0, ((Number) execute(engine, module, "safeArguments").boxed()).intValue());
        execute(engine, module, "run");
        assertTrue(host.logs.contains("value 42 true [1, 2]"));
        engine.close();
    }

    @Test
    void consoleAndCoreApiExposeAnyVariadicAndProperties() {
        VeloraEngine engine = Velora.builder().host(new TestHost()).build();
        var print = engine.api().find("console", "print");
        assertTrue(print.variadic());
        assertEquals("Any", print.parameters().get(0).type().name());
        assertFalse(print.parameters().get(0).description().isBlank());
        assertTrue(engine.api().find("math", "PI").property());
        assertFalse(engine.api().find("math", "clamp").description().isBlank());
        assertFalse(engine.api().find("math", "clamp").parameters().get(0).description().isBlank());
        assertNotNull(engine.api().find("math", "hypot"));
        assertNotNull(engine.api().find("string", "parseFloat"));
        var stdlib = java.util.Set.of("console", "log", "string", "convert", "math", "random", "time", "uuid");
        for (var descriptor : engine.api().all()) {
            if (!stdlib.contains(descriptor.namespace())) continue;
            assertFalse(descriptor.description().isBlank(), descriptor.qualifiedName());
            for (var parameter : descriptor.parameters()) assertFalse(parameter.description().isBlank(), descriptor.qualifiedName() + ":" + parameter.name());
        }
        engine.close();
    }

    @Test
    void apiPropertiesCannotBeCalledAsFunctions() {
        VeloraEngine engine = Velora.builder().host(new TestHost()).build();
        engine.freeze();
        var result = engine.compiler().compile(CompileRequest.builder("bad-property")
                .source("main.vls", "@Script(\"Bad\") script Bad { double answer() { return math.PI() } }")
                .build());
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("not callable")));
        engine.close();
    }

    @Test
    void languageServiceUsesUnifiedApiAndBuiltinMetadata() {
        VeloraEngine engine = Velora.builder().host(new TestHost()).build();
        engine.freeze();
        String source = """
                @Script("Hints")
                script Hints {
                    String text = "hello"
                    List<int> values = [1, 2]
                    run() {
                        String local = "world"
                        value = local.substring(1, 3)
                        size = values.first()
                        angle = math.clamp(2.0, 0.0, 1.0)
                        pi = math.PI
                    }
                }
                """;
        try (var editor = engine.language().openEditor("hints", "main.vls")) {
            editor.updateText(source);
            assertTrue(editor.completions(1, 1).stream().anyMatch(item -> item.label().equals("math") && item.documentation() != null && item.documentation().contains("Numeric")));
            assertTrue(editor.completions(1, 1).stream().anyMatch(item -> item.label().equals("Duration") && item.documentation() != null && item.documentation().contains("500.ms")));
            String localLine = source.lines().toList().get(6);
            int localDot = localLine.indexOf("local.") + "local.".length() + 1;
            var localItems = editor.completions(7, localDot);
            assertTrue(localItems.stream().anyMatch(item -> item.label().equals("substring") && item.documentation() != null && !item.documentation().isBlank()));
            assertTrue(localItems.stream().anyMatch(item -> item.label().equals("length") && item.kind() == CompletionItem.CompletionKind.PROPERTY));

            String mathLine = source.lines().toList().get(8);
            int mathDot = mathLine.indexOf("math.") + "math.".length() + 1;
            var mathItems = editor.completions(9, mathDot);
            assertTrue(mathItems.stream().anyMatch(item -> item.label().equals("PI") && item.kind() == CompletionItem.CompletionKind.PROPERTY && item.detail().contains("Double")));
            assertTrue(mathItems.stream().anyMatch(item -> item.label().equals("clamp") && item.detail().contains("value: Double") && item.documentation().contains("Clamps")));

            int comma = mathLine.indexOf(',', mathLine.indexOf("clamp")) + 2;
            var signature = editor.signatureHelp(9, comma).orElseThrow();
            assertEquals("math.clamp", signature.functionName());
            assertEquals("value", signature.parameters().get(0).name());
            assertTrue(signature.parameters().get(0).documentation().contains("clamp"));

            String piLine = source.lines().toList().get(9);
            int hoverColumn = piLine.indexOf("PI") + 1;
            assertTrue(editor.hover(10, hoverColumn).orElseThrow().content().contains("math.PI: Double"));
        }
        engine.close();
    }

    @Test
    void javaImportsExposeVarargsAndParameterDocumentation() {
        VeloraEngine engine = Velora.builder().host(new TestHost()).build();
        engine.javaImports().register(DocUtil.class);
        engine.freeze();
        String source = """
                import client.util.DocUtil
                @Script("Docs")
                script Docs {
                    String answer() { return DocUtil.join("x", 1, true) }
                }
                """;
        CompiledModule module = compile(engine, "docs", source);
        assertEquals("x:1:true", execute(engine, module, "answer").boxed());
        try (var editor = engine.language().openEditor("docs", "main.vls")) {
            editor.updateText(source);
            String line = source.lines().toList().get(3);
            int dot = line.indexOf("DocUtil.") + "DocUtil.".length() + 1;
            var importCompletion = editor.completions(2, 1).stream().filter(item -> item.label().equals("DocUtil")).findFirst().orElseThrow();
            assertTrue(importCompletion.documentation().contains("documented client utility"));
            assertTrue(editor.hover(4, line.indexOf("DocUtil") + 1).orElseThrow().content().contains("client.util.DocUtil"));
            var completion = editor.completions(4, dot).stream().filter(item -> item.label().equals("join")).findFirst().orElseThrow();
            assertTrue(completion.detail().contains("values...: Any"));
            assertTrue(completion.documentation().contains("arbitrary values"));
            int secondArgument = line.indexOf(',', line.indexOf("join")) + 2;
            var signature = editor.signatureHelp(4, secondArgument).orElseThrow();
            assertEquals(1, signature.activeParameter());
            assertEquals("values...", signature.parameters().get(1).name());
            assertTrue(signature.parameters().get(0).documentation().contains("Prefix text"));
            assertTrue(signature.parameters().get(1).documentation().contains("Values to append"));
        }
        engine.close();
    }


    @Test
    void structPropertyDescriptionsReachMemberCompletion() {
        class Player { String name() { return "Steve"; } }
        VeloraEngine engine = Velora.builder().host(new TestHost()).build();
        engine.types().struct("Player", Player.class, type -> type.property("name", io.velora.api.type.VeloraTypes.STRING, "Current player display name", value -> ((Player) value).name()));
        engine.freeze();
        String source = "@Script(\"StructDocs\") script StructDocs { String inspect(Player player) { return player.name } }";
        try (var editor = engine.language().openEditor("struct-docs", "main.vls")) {
            editor.updateText(source);
            int dot = source.indexOf("player.name") + "player.".length() + 1;
            var item = editor.completions(1, dot).stream().filter(completion -> completion.label().equals("name")).findFirst().orElseThrow();
            assertEquals("Current player display name", item.documentation());
            assertEquals(CompletionItem.CompletionKind.PROPERTY, item.kind());
        }
        engine.close();
    }


    @Test
    void memberToolingHandlesSafeAccessChainsVectorsAndMalformedTypes() {
        class Position { String label() { return "spawn"; } }
        class Player { Position position() { return new Position(); } }
        VeloraEngine engine = Velora.builder().host(new TestHost()).build();
        var positionType = engine.types().struct("Position", Position.class, type ->
                type.property("label", io.velora.api.type.VeloraTypes.STRING, "Position label", value -> ((Position) value).label()));
        engine.types().struct("Player", Player.class, type ->
                type.property("position", positionType, "Current player position", value -> ((Player) value).position()));
        engine.freeze();
        String source = """
                @Script("MemberHints")
                script MemberHints {
                    String inspect(Player? player, Vec3 point) {
                        name = player?.position?.label
                        value = player?.position?.label
                        String? text = "abc"
                        sub = text?.substring(1, 2)
                        return name
                    }
                }
                """;
        try (var editor = engine.language().openEditor("member-hints", "main.vls")) {
            editor.updateText(source);
            String safeProbe = "@Script(\"Safe\") script Safe { run(Player? player) { value = player?. } }";
            editor.updateText(safeProbe);
            int safeColumn = safeProbe.indexOf("player?.") + "player?.".length() + 1;
            var safeItems = editor.completions(1, safeColumn);
            assertTrue(safeItems.stream().anyMatch(item -> item.label().equals("position") && item.documentation().contains("Current player position")));

            String chainProbe = "@Script(\"Chain\") script Chain { run(Player player) { value = player.position. } }";
            editor.updateText(chainProbe);
            int chainColumn = chainProbe.indexOf("player.position.") + "player.position.".length() + 1;
            var chainItems = editor.completions(1, chainColumn);
            assertTrue(chainItems.stream().anyMatch(item -> item.label().equals("label") && item.documentation().equals("Position label")));

            String vectorProbe = "@Script(\"Vector\") script Vector { run(Vec3 point) { value = point. } }";
            editor.updateText(vectorProbe);
            int vectorColumn = vectorProbe.indexOf("point.") + "point.".length() + 1;
            var vectorItems = editor.completions(1, vectorColumn);
            assertTrue(vectorItems.stream().anyMatch(item -> item.label().equals("x") && item.kind() == CompletionItem.CompletionKind.PROPERTY));
            assertTrue(vectorItems.stream().anyMatch(item -> item.label().equals("z") && item.kind() == CompletionItem.CompletionKind.PROPERTY));

            editor.updateText(source);
            String line = source.lines().toList().get(6);
            int signatureColumn = line.indexOf(',', line.indexOf("substring")) + 2;
            var signature = editor.signatureHelp(7, signatureColumn).orElseThrow();
            assertEquals("substring", signature.functionName());
            assertEquals("start", signature.parameters().get(0).name());

            String hoverLine = source.lines().toList().get(3);
            int hoverColumn = hoverLine.indexOf("label") + 1;
            assertTrue(editor.hover(4, hoverColumn).orElseThrow().content().contains("label: String"));

            String malformed = "@Script(\"Malformed\") script Malformed { run() { List<Missing> values = list<Missing>() value = values. } }";
            editor.updateText(malformed);
            int malformedColumn = malformed.indexOf("values.") + "values.".length() + 1;
            assertDoesNotThrow(() -> editor.completions(1, malformedColumn));
        }
        engine.close();
    }

    @Test
    void registryHashTracksPropertyAndVariadicAbi() {
        String source = "@Script(\"Abi\") script Abi { int answer() { return 42 } }";

        VeloraEngine functionEngine = Velora.builder().host(new TestHost()).build();
        functionEngine.api().namespace("abi", ns -> ns.function("value", io.velora.api.type.VeloraTypes.INT, ctx -> 1));
        functionEngine.freeze();
        String functionHash = functionEngine.compiler().compile(CompileRequest.builder("abi").source("main.vls", source).build()).registryHash();

        VeloraEngine propertyEngine = Velora.builder().host(new TestHost()).build();
        propertyEngine.api().namespace("abi", ns -> ns.property("value", io.velora.api.type.VeloraTypes.INT, ctx -> 1));
        propertyEngine.freeze();
        String propertyHash = propertyEngine.compiler().compile(CompileRequest.builder("abi").source("main.vls", source).build()).registryHash();

        VeloraEngine requiredEngine = Velora.builder().host(new TestHost()).build();
        requiredEngine.api().namespace("abi", ns -> ns.function("collect", io.velora.api.type.VeloraTypes.UNIT, p -> p.required("values", io.velora.api.type.VeloraTypes.ANY), ctx -> null));
        requiredEngine.freeze();
        String requiredHash = requiredEngine.compiler().compile(CompileRequest.builder("abi").source("main.vls", source).build()).registryHash();

        VeloraEngine variadicEngine = Velora.builder().host(new TestHost()).build();
        variadicEngine.api().namespace("abi", ns -> ns.function("collect", io.velora.api.type.VeloraTypes.UNIT, p -> p.variadic("values", io.velora.api.type.VeloraTypes.ANY), ctx -> null));
        variadicEngine.freeze();
        String variadicHash = variadicEngine.compiler().compile(CompileRequest.builder("abi").source("main.vls", source).build()).registryHash();

        assertNotEquals(functionHash, propertyHash);
        assertNotEquals(requiredHash, variadicHash);
        functionEngine.close();
        propertyEngine.close();
        requiredEngine.close();
        variadicEngine.close();
    }

    private CompiledModule compile(VeloraEngine engine, String id, String source) {
        CompileRequest request = CompileRequest.builder(id).source("main.vls", source).build();
        var result = engine.compiler().compile(request);
        assertTrue(result.success(), result.diagnostics().toString());
        return ((DefaultScriptCompiler) engine.compiler()).compileToModule(request);
    }

    private ScriptValue execute(VeloraEngine engine, CompiledModule module, String function) {
        var result = new VirtualMachine(engine.api(), module.settings(), 100_000)
                .execute(module, module.functionByName(function).index(), new ScriptValue[0]);
        assertTrue(result.success(), result.error() == null ? "VM failed" : result.error().message());
        return result.returnValue();
    }

    private static final class TestHost implements VeloraHost {
        private final List<String> logs = new ArrayList<>();
        @Override public String id() { return "test"; }
        @Override public String version() { return "1"; }
        @Override public MainThreadExecutor mainThread() { return new MainThreadExecutor() {
            @Override public boolean isMainThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        }; }
        @Override public WorkerExecutor workers() { return new WorkerExecutor() {
            @Override public void execute(Runnable action) { action.run(); }
            @Override public void shutdown() { }
        }; }
        @Override public VeloraClock clock() { return new VeloraClock() {
            @Override public long nanoTime() { return System.nanoTime(); }
            @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
        }; }
        @Override public VeloraLogger logger() { return new VeloraLogger() {
            @Override public void debug(String message) { logs.add(message); }
            @Override public void info(String message) { logs.add(message); }
            @Override public void warn(String message) { logs.add(message); }
            @Override public void error(String message, Throwable error) { logs.add(message); }
        }; }
        @Override public VeloraFileSystem fileSystem() { return null; }
    }
}
