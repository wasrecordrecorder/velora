package io.velora.internal.parser;

import io.velora.internal.ast.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParserV2Test {
    private ScriptNode parse(String source) {
        ParseResult result = Parser.parse(source, "main.vls");
        assertFalse(result.hasErrors(), "Parser errors: " + result.diagnostics());
        assertNotNull(result.scriptNode());
        return result.scriptNode();
    }

    @Test
    @DisplayName("Script metadata annotations parse independently")
    void metadataAnnotations() {
        ScriptNode node = parse("@Script(\"Example\")\n@Version(\"1.0\")\n@Author(\"syntax\")\n@Description(\"demo\")\nscript Example {}");
        assertEquals("Example", node.scriptName());
        assertEquals(4, node.annotations().size());
        assertEquals("Script", node.annotations().get(0).name());
        assertEquals("Version", node.annotations().get(1).name());
    }

    @Test
    void minimalScript() {
        ScriptNode node = parse("@Script(\"Minimal\")\nscript Minimal {}");
        assertEquals("Minimal", node.scriptName());
        assertTrue(node.members().isEmpty());
    }

    @Test
    void inlineSettingWithInference() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { @Setting(\"Enabled\") enabled = true }");
        assertNotNull(node.settingBlock());
        SettingDeclarationNode setting = node.settingBlock().declarations().get(0);
        assertEquals("enabled", setting.identifier());
        assertNull(setting.declaredType());
        assertEquals("Enabled", setting.positionalArguments().get(0));
    }

    @Test
    void inlineSettingWithConstraints() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { @Setting(\"Range\", min=1.0, max=6.0) range = 4.2 }");
        SettingDeclarationNode setting = node.settingBlock().declarations().get(0);
        assertEquals(1.0, setting.namedArguments().get("min"));
        assertEquals(6.0, setting.namedArguments().get("max"));
    }

    @Test
    void inferredField() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { lastAttack = 0 }");
        PropertyDeclarationNode field = (PropertyDeclarationNode) node.members().get(0);
        assertEquals("lastAttack", field.name());
        assertNull(field.declaredType());
    }

    @Test
    void explicitFieldTypeStillAllowed() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { long lastAttack = 0 }");
        PropertyDeclarationNode field = (PropertyDeclarationNode) node.members().get(0);
        assertEquals("long", field.declaredType().typeName());
    }

    @Test
    void persistentField() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { @Persistent launches = 0 }");
        PropertyDeclarationNode field = (PropertyDeclarationNode) node.members().get(0);
        assertTrue(field.persistent());
    }

    @Test
    void inferredFunctionReturnType() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { answer() { return 42 } }");
        FunctionNode function = (FunctionNode) node.members().get(0);
        assertEquals("answer", function.name());
        assertNull(function.returnType());
    }

    @Test
    void explicitFunctionReturnTypeStillAllowed() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { int answer() { return 42 } }");
        FunctionNode function = (FunctionNode) node.members().get(0);
        assertEquals("int", function.returnType().typeName());
    }

    @Test
    void typedFunctionParameters() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { distance(Player source, int max = 5) { return max } }");
        FunctionNode function = (FunctionNode) node.members().get(0);
        assertEquals(2, function.parameters().size());
        assertEquals("Player", function.parameters().get(0).type().typeName());
        assertTrue(function.parameters().get(1).hasDefault());
    }

    @Test
    void lifecycleAnnotationLivesOnFunction() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { @Enable enable() { } }");
        FunctionNode function = (FunctionNode) node.members().get(0);
        assertEquals("Enable", function.annotations().get(0).name());
        assertEquals("enable", function.name());
    }

    @Test
    void dynamicEventAnnotationLivesOnFunction() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { @Tick tick(TickEvent event) { } }");
        FunctionNode function = (FunctionNode) node.members().get(0);
        assertEquals("Tick", function.annotations().get(0).name());
        assertEquals("TickEvent", function.parameters().get(0).type().typeName());
    }

    @Test
    void asyncAnnotatedHandler() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { @Tick async tick() { delay(1.seconds) } }");
        FunctionNode function = (FunctionNode) node.members().get(0);
        assertTrue(function.suspending());
    }

    @Test
    void listConstructor() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { bots = list<Player>() }");
        PropertyDeclarationNode field = (PropertyDeclarationNode) node.members().get(0);
        CollectionConstructorExpressionNode value = (CollectionConstructorExpressionNode) field.initializer();
        assertEquals(CollectionConstructorExpressionNode.Kind.LIST, value.kind());
        assertEquals("Player", value.typeArguments().get(0).typeName());
    }

    @Test
    void setConstructor() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { ids = set<UUID>() }");
        CollectionConstructorExpressionNode value = (CollectionConstructorExpressionNode) ((PropertyDeclarationNode) node.members().get(0)).initializer();
        assertEquals(CollectionConstructorExpressionNode.Kind.SET, value.kind());
    }

    @Test
    void mapConstructor() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { counts = map<String, int>() }");
        CollectionConstructorExpressionNode value = (CollectionConstructorExpressionNode) ((PropertyDeclarationNode) node.members().get(0)).initializer();
        assertEquals(CollectionConstructorExpressionNode.Kind.MAP, value.kind());
        assertEquals(2, value.typeArguments().size());
    }

    @Test
    void typedForIn() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { run(List<Player> players) { for (Player player in players) { } } }");
        FunctionNode function = (FunctionNode) node.members().get(0);
        ForStatementNode loop = (ForStatementNode) function.body().statements().get(0);
        assertEquals("Player", loop.variableType().typeName());
        assertEquals("player", loop.variable());
    }

    @Test
    void inferredForIn() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { run(List<Player> players) { for (player in players) { } } }");
        FunctionNode function = (FunctionNode) node.members().get(0);
        ForStatementNode loop = (ForStatementNode) function.body().statements().get(0);
        assertNull(loop.variableType());
    }

    @Test
    void inferredLocalAssignment() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { answer() { value = 42\n return value } }");
        FunctionNode function = (FunctionNode) node.members().get(0);
        ExpressionStatementNode statement = (ExpressionStatementNode) function.body().statements().get(0);
        assertTrue(statement.expression() instanceof AssignmentExpressionNode);
    }

    @Test
    void commentsAreIgnoredByParser() {
        ScriptNode node = parse("@Script(\"T\")\nscript T { // line\n /* block */ answer() { return 42 } }");
        assertEquals(1, node.members().size());
    }

    @Test
    void oldSettingsBlockIsRejected() {
        ParseResult result = Parser.parse("@Script(\"T\")\nscript T { settings { } }", "main.vls");
        assertTrue(result.hasErrors());
    }

    @Test
    void oldEntrySyntaxIsRejected() {
        ParseResult result = Parser.parse("@Script(\"T\")\nscript T { entry onEnable() { } }", "main.vls");
        assertTrue(result.hasErrors());
    }

    @Test
    void oldEventKeywordIsRejected() {
        ParseResult result = Parser.parse("@Script(\"T\")\nscript T { event Tick() { } }", "main.vls");
        assertTrue(result.hasErrors());
    }

    @Test
    void oldVoidKeywordIsRejected() {
        ParseResult result = Parser.parse("@Script(\"T\")\nscript T { void run() { } }", "main.vls");
        assertTrue(result.hasErrors());
    }

    @Test
    void oldDottedEventAnnotationIsRejected() {
        ParseResult result = Parser.parse("@Script(\"T\")\nscript T { @Event.Tick tick() { } }", "main.vls");
        assertTrue(result.hasErrors());
    }

    @Test
    void packageAndImportAreRejected() {
        assertTrue(Parser.parse("package old\n@Script(\"T\")\nscript T {}", "main.vls").hasErrors());
        assertTrue(Parser.parse("import old.api\n@Script(\"T\")\nscript T {}", "main.vls").hasErrors());
    }
}
