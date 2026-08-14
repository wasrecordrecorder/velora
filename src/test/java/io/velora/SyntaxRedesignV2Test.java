package io.velora;

import io.velora.api.compiler.*;
import io.velora.api.event.EventDescriptor;
import io.velora.api.type.VeloraTypes;
import io.velora.internal.bytecode.CompiledModule;
import io.velora.internal.compiler.DefaultScriptCompiler;
import io.velora.internal.event.DefaultEventRegistry;
import io.velora.internal.language.CompletionEngine;
import io.velora.internal.language.DefinitionEngine;
import io.velora.internal.registry.DefaultApiRegistry;
import io.velora.internal.registry.DefaultConstantRegistry;
import io.velora.internal.registry.DefaultTypeRegistry;
import io.velora.internal.setting.DefaultSettingRegistry;
import io.velora.internal.vm.ScriptValue;
import io.velora.internal.vm.VirtualMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyntaxRedesignV2Test {
    private DefaultTypeRegistry types;
    private DefaultSettingRegistry settings;
    private DefaultConstantRegistry constants;
    private DefaultApiRegistry api;
    private DefaultEventRegistry events;
    private DefaultScriptCompiler compiler;

    @BeforeEach
    void setUp() {
        types = new DefaultTypeRegistry();
        settings = new DefaultSettingRegistry();
        constants = new DefaultConstantRegistry();
        api = new DefaultApiRegistry(types);
        events = new DefaultEventRegistry();
        compiler = new DefaultScriptCompiler(types, settings, api, constants, events);
    }

    private CompileResult compile(String source) {
        return compiler.compile(CompileRequest.builder("T").source("main.vls", source).build());
    }

    private CompiledModule module(String source) {
        CompileRequest request = CompileRequest.builder("T").source("main.vls", source).build();
        CompileResult result = compiler.compile(request);
        assertTrue(result.success(), "Diagnostics: " + result.diagnostics());
        return compiler.compileToModule(request);
    }

    private Object execute(CompiledModule module, String function) {
        var result = new VirtualMachine(api, module.settings(), 100_000)
                .execute(module, module.functionByName(function).index(), new ScriptValue[0]);
        assertTrue(result.success(), result.error() == null ? "VM failed" : result.error().message());
        return result.returnValue().boxed();
    }

    @Test
    void minimalScriptNeedsOnlyScriptMetadata() {
        CompileResult result = compile("@Script(\"T\")\nscript T { answer() { return 42 } }");
        assertTrue(result.success(), "Diagnostics: " + result.diagnostics());
    }

    @Test
    void versionAuthorAndDescriptionAreOptionalIndependentMetadata() {
        CompiledModule module = module("@Script(\"Visible\")\n@Version(\"2.4\")\n@Author(\"syntax\")\n@Description(\"demo\")\nscript T { answer() { return 1 } }");
        assertEquals("Visible", module.scriptName());
        assertEquals("2.4", module.version());
        assertEquals("syntax", module.author());
        assertEquals("demo", module.description());
    }

    @Test
    void scriptCategoryIsNotSupported() {
        CompileResult result = compile("@Script(\"T\")\n@Category(\"Combat\")\nscript T {}");
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION));
    }

    @Test
    void inferredFieldsLocalsAndReturnTypeExecute() {
        CompiledModule module = module("@Script(\"T\")\nscript T { seed = 20 answer() { extra = 22 return seed + extra } }");
        assertEquals(42, execute(module, "answer"));
    }

    @Test
    void inferredNumericStateWidensWhenAssignedFromLongApi() {
        api.namespace("time", ns -> ns.function("millis", VeloraTypes.LONG, ctx -> 42L));
        CompiledModule module = module("@Script(\"T\")\nscript T { lastAttack = 0 update() { lastAttack = time.millis() return lastAttack } }");
        assertEquals(42L, execute(module, "update"));
    }

    @Test
    void localShadowDoesNotWidenInferredScriptField() {
        api.namespace("time", ns -> ns.function("millis", VeloraTypes.LONG, ctx -> 42L));
        api.namespace("ints", ns -> ns.function("echo", VeloraTypes.INT, p -> p.required("value", VeloraTypes.INT), ctx -> ctx.argument(0, Integer.class)));
        CompileResult result = compile("@Script(\"T\")\nscript T { value = 0 shadow() { long value = 0 value = time.millis() } answer() { return ints.echo(value) } }");
        assertTrue(result.success(), "Diagnostics: " + result.diagnostics());
    }

    @Test
    void nullStillRequiresAnExplicitType() {
        CompileResult result = compile("@Script(\"T\")\nscript T { value = null }");
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_UNTYPED_DECLARATION));
        assertTrue(compile("@Script(\"T\")\nscript T { String? value = null }").success());
    }

    @Test
    void inlineSettingsInferPrimitiveTypes() {
        CompiledModule module = module("@Script(\"T\")\nscript T { @Setting(\"Enabled\") enabled = true @Setting(\"Range\", min=1.0, max=6.0) range = 4.2 answer() { if (enabled) { return range } return 0.0 } }");
        assertEquals(2, module.settings().size());
        assertEquals(VeloraTypes.BOOLEAN, module.settings().get(0).type());
        assertEquals(VeloraTypes.DOUBLE, module.settings().get(1).type());
        assertEquals(4.2, ((Number) execute(module, "answer")).doubleValue(), 0.0001);
    }

    @Test
    void commentsWorkWithTheNewSyntax() {
        CompiledModule module = module("@Script(\"T\")\nscript T { // field\n value = 40 /* block\n comment */ answer() { // local\n x = 2 return value + x } }");
        assertEquals(42, execute(module, "answer"));
    }

    @Test
    void typedCollectionConstructorsAndMemberMethodsExecute() {
        CompiledModule module = module("@Script(\"T\")\nscript T { values = list<int>() answer() { values.add(10) values.add(32) had = values.contains(10) values.remove(10) if (had) { return values.size + values[0] } return 0 } }");
        assertEquals(33, execute(module, "answer"));
    }

    @Test
    void setAndMapConstructorsExecute() {
        CompiledModule module = module("@Script(\"T\")\nscript T { ids = set<int>() scores = map<String, int>() answer() { ids.add(4) ids.add(4) scores.put(\"x\", 38) if (ids.contains(4) && scores.containsKey(\"x\")) { return ids.size + scores[\"x\"] } return 0 } }");
        assertEquals(39, execute(module, "answer"));
    }

    @Test
    void inferredForVariableWorks() {
        CompiledModule module = module("@Script(\"T\")\nscript T { answer() { values = [10, 20, 12] total = 0 for (value in values) { total += value } return total } }");
        assertEquals(42, execute(module, "answer"));
    }

    @Test
    void lifecycleAnnotationCompilesToRuntimeHookName() {
        CompiledModule module = module("@Script(\"T\")\nscript T { state = 0 @Enable start() { state = 42 } answer() { return state } }");
        assertNotNull(module.functionByName("ON_ENABLE"));
        VirtualMachine vm = new VirtualMachine(api, module.settings(), 100_000);
        var enabled = vm.execute(module, module.functionByName("ON_ENABLE").index(), new ScriptValue[0]);
        assertTrue(enabled.success());
        var answer = vm.execute(module, module.functionByName("answer").index(), new ScriptValue[0]);
        assertTrue(answer.success());
        assertEquals(42, answer.returnValue().boxed());
    }

    @Test
    void eventDescriptorsDeriveReachableAnnotationNamesAndRejectConflicts() {
        EventDescriptor derived = EventDescriptor.builder("client.packet-receive").payloadType(VeloraTypes.UNIT).build();
        assertEquals("PacketReceive", derived.scriptName());
        assertThrows(IllegalArgumentException.class, () -> EventDescriptor.builder("client.tick").scriptName("client.tick").payloadType(VeloraTypes.UNIT).build());
        assertThrows(IllegalArgumentException.class, () -> EventDescriptor.builder("client.enable").scriptName("Enable").payloadType(VeloraTypes.UNIT).build());
    }

    @Test
    void dynamicPayloadlessEventIsResolvedFromRegistry() {
        events.register(EventDescriptor.builder("client.tick").scriptName("Tick").payloadType(VeloraTypes.UNIT).build());
        CompiledModule module = module("@Script(\"T\")\nscript T { @Tick update() {} }");
        assertEquals(1, module.eventHandlers().size());
        assertEquals("client.tick", module.eventHandlers().get(0).eventReference());
    }

    @Test
    void dynamicPayloadEventChecksTheRegisteredType() {
        events.register(EventDescriptor.builder("client.score").scriptName("Score").payloadType(VeloraTypes.INT).build());
        assertTrue(compile("@Script(\"T\")\nscript T { @Score score(int value) {} }").success());
        CompileResult wrong = compile("@Script(\"T\")\nscript T { @Score score(String value) {} }");
        assertFalse(wrong.success());
        assertTrue(wrong.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_TYPE_MISMATCH));
    }

    @Test
    void unknownHandlerAnnotationIsRejected() {
        CompileResult result = compile("@Script(\"T\")\nscript T { @DoesNotExist handler() {} }");
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION));
    }

    @Test
    void multipleHandlersMaySubscribeToTheSameRegisteredEvent() {
        events.register(EventDescriptor.builder("client.tick").scriptName("Tick").payloadType(VeloraTypes.UNIT).build());
        CompiledModule module = module("@Script(\"T\")\nscript T { @Tick first() {} @Tick second() {} }");
        assertEquals(2, module.eventHandlers().size());
    }

    @Test
    void oldSourceSyntaxIsRejectedInsteadOfDeprecated() {
        String[] removed = {
                "@Script(\"T\") script T { settings { } }",
                "@Script(\"T\") script T { entry onEnable() {} }",
                "@Script(\"T\") script T { event Tick() {} }",
                "@Script(\"T\") script T { void run() {} }",
                "@Script(\"T\") script T { @Event.Tick tick() {} }",
                "@Script(\"T\") @Permissions(\"x\") script T {}"
        };
        for (String source : removed) assertFalse(compile(source).success(), source);
    }


    @Test
    void canonicalMinecraftStyleScriptCompilesAgainstRegisteredBindings() {
        record Player(boolean self, double distance) {}
        var playerType = types.struct("Player", Player.class, b -> b
                .property("self", VeloraTypes.BOOLEAN, value -> ((Player) value).self())
                .property("distance", VeloraTypes.DOUBLE, value -> ((Player) value).distance()));
        api.namespace("world", ns -> ns.function("players", VeloraTypes.list(playerType), ctx -> List.of()));
        events.register(EventDescriptor.builder("client.tick").payloadType(VeloraTypes.UNIT).build());
        String source = """
                @Script("Example")
                @Version("1.0")
                @Author("syntax")
                script Example {
                    @Setting("Enabled") enabled = true
                    @Setting("Range", min=1.0, max=6.0) range = 4.2

                    targets = list<Player>()
                    lastAttack = 0

                    // Reset transient state on enable.
                    @Enable
                    enable() {
                        targets.clear()
                    }

                    /* Registered client event. */
                    @Tick
                    tick() {
                        if (!enabled) {
                            return
                        }
                        for (Player player in world.players()) {
                            if (!player.self && player.distance <= range && !targets.contains(player)) {
                                targets.add(player)
                            }
                        }
                    }

                    findCount() {
                        return targets.size
                    }
                }
                """;
        CompileResult result = compile(source);
        assertTrue(result.success(), "Diagnostics: " + result.diagnostics());
    }

    @Test
    void languageServiceFindsInferredFunctionAndLocalDefinitions() {
        String source = "@Script(\"T\")\nscript T {\n    answer() { return 42 }\n\n    run() {\n        value = answer()\n        return value\n    }\n}";
        var function = DefinitionEngine.getDefinition(source, 6, 17, "main.vls").orElseThrow();
        assertEquals(3, function.line());
        assertEquals(5, function.column());
        var local = DefinitionEngine.getDefinition(source, 7, 16, "main.vls").orElseThrow();
        assertEquals(6, local.line());
        assertEquals(9, local.column());
    }

    @Test
    void languageServiceCompletesRegisteredEventAnnotationsAndOnlyV2Keywords() {
        events.register(EventDescriptor.builder("client.tick").scriptName("Tick").payloadType(VeloraTypes.UNIT).description("Client tick").build());
        List<String> annotations = CompletionEngine.getCompletions("@T", 1, 3, api, types, events, settings, constants)
                .stream().map(item -> item.label()).toList();
        assertTrue(annotations.contains("@Tick"));

        List<String> root = CompletionEngine.getCompletions("", 1, 1, api, types, events, settings, constants)
                .stream().map(item -> item.label()).toList();
        assertTrue(root.contains("script"));
        assertTrue(root.contains("list"));
        assertFalse(root.contains("settings"));
        assertFalse(root.contains("entry"));
        assertFalse(root.contains("event"));
        assertFalse(root.contains("void"));
    }

    @Test
    void languageVersionOneIsRejected() {
        CompileResult result = compiler.compile(CompileRequest.builder("T")
                .languageVersion(1)
                .source("main.vls", "@Script(\"T\") script T {}")
                .build());
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.COMPILER_UNSUPPORTED_VERSION));
    }
}
