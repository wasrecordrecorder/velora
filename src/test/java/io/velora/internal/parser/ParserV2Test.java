package io.velora.internal.parser;

import io.velora.internal.ast.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class ParserV2Test {

    private ParseResult parse(String source) {
        return Parser.parse(source, "test.vls");
    }

    private ScriptNode parseScript(String source) {
        ParseResult r = parse(source);
        assertFalse(r.hasErrors(), () -> "Parser errors: " + r.diagnostics());
        assertNotNull(r.scriptNode());
        return r.scriptNode();
    }

    // === Minimal Script ===

    @Test
    @DisplayName("Empty script parses")
    void emptyScript() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {}");
        assertEquals("T", node.scriptName());
        assertTrue(node.members().isEmpty());
    }

    @Test
    @DisplayName("Script with @Script annotation")
    void scriptAnnotation() {
        ScriptNode node = parseScript("@Script(name=\"Test\", version=\"1.0.0\", author=\"Me\")\nscript Test {}");
        assertFalse(node.annotations().isEmpty());
    }

    @Test
    @DisplayName("Script with @Permissions annotation")
    void permissionsAnnotation() {
        ScriptNode node = parseScript("@Permissions(Permission.WORLD_READ)\nscript Test {}");
        assertFalse(node.annotations().isEmpty());
    }

    // === Fields ===

    @Test
    @DisplayName("int field declaration")
    void intField() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int x = 5\n}");
        assertEquals(1, node.members().size());
        PropertyDeclarationNode prop = (PropertyDeclarationNode) node.members().get(0);
        assertEquals("x", prop.name());
        assertEquals("int", prop.declaredType().typeName());
        assertFalse(prop.isConst());
        assertFalse(prop.isStatic());
    }

    @Test
    @DisplayName("String field with null initializer")
    void stringFieldNull() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    String home = null\n}");
        PropertyDeclarationNode prop = (PropertyDeclarationNode) node.members().get(0);
        assertEquals("home", prop.name());
        assertEquals("String", prop.declaredType().typeName());
    }

    @Test
    @DisplayName("boolean field")
    void booleanField() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    boolean enabled = true\n}");
        PropertyDeclarationNode prop = (PropertyDeclarationNode) node.members().get(0);
        assertEquals("enabled", prop.name());
        assertEquals("boolean", prop.declaredType().typeName());
    }

    @Test
    @DisplayName("static field declaration")
    void staticField() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    static int total = 0\n}");
        PropertyDeclarationNode prop = (PropertyDeclarationNode) node.members().get(0);
        assertTrue(prop.isStatic());
        assertEquals("total", prop.name());
    }

    @Test
    @DisplayName("const field with # prefix")
    void constField() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    #int MAX = 5\n}");
        PropertyDeclarationNode prop = (PropertyDeclarationNode) node.members().get(0);
        assertTrue(prop.isConst());
        assertEquals("MAX", prop.name());
        assertEquals("int", prop.declaredType().typeName());
    }

    @Test
    @DisplayName("const String field")
    void constStringField() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    #String CMD = \"/home\"\n}");
        PropertyDeclarationNode prop = (PropertyDeclarationNode) node.members().get(0);
        assertTrue(prop.isConst());
        assertEquals("CMD", prop.name());
    }

    // === Methods ===

    @Test
    @DisplayName("int method with return")
    void intMethod() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int answer() { return 42 }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        assertEquals("answer", fn.name());
        assertEquals("int", fn.returnType().typeName());
        assertFalse(fn.suspending());
    }

    @Test
    @DisplayName("void method")
    void voidMethod() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    void reset() { }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        assertEquals("reset", fn.name());
    }

    @Test
    @DisplayName("async void method")
    void asyncMethod() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    async void run() { delay(500) }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        assertEquals("run", fn.name());
        assertTrue(fn.suspending());
    }

    @Test
    @DisplayName("method with typed parameters")
    void methodWithParams() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int add(int a, int b) { return a + b }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        assertEquals(2, fn.parameters().size());
        assertEquals("a", fn.parameters().get(0).name());
        assertEquals("b", fn.parameters().get(1).name());
    }

    @Test
    @DisplayName("method with default parameter")
    void methodWithDefaultParam() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int add(int a, int b = 2) { return a + b }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        assertEquals(2, fn.parameters().size());
    }

    // === Entry Points ===

    @Test
    @DisplayName("entry onLoad")
    void entryOnLoad() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    entry onLoad() { }\n}");
        LifecycleNode lc = (LifecycleNode) node.members().get(0);
        assertEquals(LifecycleNode.Hook.ON_LOAD, lc.hook());
        assertFalse(lc.suspending());
    }

    @Test
    @DisplayName("async entry onRun")
    void asyncEntryOnRun() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    async entry onRun() { }\n}");
        LifecycleNode lc = (LifecycleNode) node.members().get(0);
        assertEquals(LifecycleNode.Hook.ON_RUN, lc.hook());
        assertTrue(lc.suspending());
    }

    @Test
    @DisplayName("entry onUnload")
    void entryOnUnload() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    entry onUnload() { }\n}");
        LifecycleNode lc = (LifecycleNode) node.members().get(0);
        assertEquals(LifecycleNode.Hook.ON_UNLOAD, lc.hook());
    }

    // === Events ===

    @Test
    @DisplayName("event handler")
    void eventHandler() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    event onTick(TickEvent e) { return false }\n}");
        EventHandlerNode ev = (EventHandlerNode) node.members().get(0);
        assertEquals("e", ev.parameterName());
        assertFalse(ev.suspending());
    }

    @Test
    @DisplayName("async event handler")
    void asyncEventHandler() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    async event onTick(TickEvent e) { return false }\n}");
        EventHandlerNode ev = (EventHandlerNode) node.members().get(0);
        assertTrue(ev.suspending());
    }

    @Test
    @DisplayName("event with @Event annotation")
    void eventWithAnnotation() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    @Event.ChatMessage\n    event onMessage(ChatMessageEvent msg) { return false }\n}");
        EventHandlerNode ev = (EventHandlerNode) node.members().get(0);
        assertNotNull(ev.eventReference());
    }

    // === Settings Block ===

    @Test
    @DisplayName("settings block with @Number")
    void settingsBlock() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    settings {\n        @Number radius (\"R\", 8..128, 1, 32, @Number.Slider)\n    }\n}");
        assertNotNull(node.settingBlock());
        assertEquals(1, node.settingBlock().declarations().size());
        SettingDeclarationNode decl = node.settingBlock().declarations().get(0);
        assertEquals("radius", decl.identifier());
    }

    @Test
    @DisplayName("settings block with @String")
    void settingsStringBlock() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    settings {\n        @String target (\"T\", 1..50, \"ore\", @String.Small)\n    }\n}");
        assertEquals(1, node.settingBlock().declarations().size());
    }

    @Test
    @DisplayName("settings block with @Boolean")
    void settingsBooleanBlock() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    settings {\n        @Boolean enabled (\"E\", true)\n    }\n}");
        assertEquals(1, node.settingBlock().declarations().size());
    }

    @Test
    @DisplayName("settings block with multiple declarations")
    void settingsMultiple() {
        ScriptNode node = parseScript("""
            @Script(name="T", version="1")
            script T {
                settings {
                    @Number radius ("R", 8..128, 1, 32, @Number.Slider)
                    @String target ("T", 1..50, "ore", @String.Small)
                    @Boolean enabled ("E", true)
                }
            }
            """);
        assertEquals(3, node.settingBlock().declarations().size());
    }

    // === Control Flow ===

    @Test
    @DisplayName("if statement")
    void ifStatement() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int x() { if (true) { return 1 } return 0 }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        IfStatementNode ifStmt = (IfStatementNode) fn.body().statements().get(0);
        assertNotNull(ifStmt.condition());
        assertNotNull(ifStmt.thenBlock());
    }

    @Test
    @DisplayName("if-else statement")
    void ifElseStatement() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int x() { if (true) { return 1 } else { return 0 } }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        IfStatementNode ifStmt = (IfStatementNode) fn.body().statements().get(0);
        assertNotNull(ifStmt.elseBlock());
    }

    @Test
    @DisplayName("while loop")
    void whileLoop() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int x() { int i = 0\n while (i < 10) { i++ } return i }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        WhileStatementNode whileStmt = (WhileStatementNode) fn.body().statements().get(1);
        assertNotNull(whileStmt.condition());
        assertNotNull(whileStmt.body());
    }

    @Test
    @DisplayName("for-in loop")
    void forInLoop() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int x() { List<int> v = [1, 2, 3]\n int s = 0\n for (int i in v) { s += i } return s }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        ForStatementNode forStmt = (ForStatementNode) fn.body().statements().get(2);
        assertEquals("i", forStmt.variable());
        assertNotNull(forStmt.iterable());
    }

    // === Expressions ===

    @Test
    @DisplayName("Binary arithmetic expression")
    void binaryArithmetic() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int x() { return 6 * 7 }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        ReturnStatementNode ret = (ReturnStatementNode) fn.body().statements().get(0);
        BinaryExpressionNode bin = (BinaryExpressionNode) ret.value();
        assertEquals("*", bin.operator());
    }

    @Test
    @DisplayName("String interpolation expression")
    void interpolation() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    String x() { int v = 42\n return \"v${v}\" }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        ReturnStatementNode ret = (ReturnStatementNode) fn.body().statements().get(1);
        assertTrue(ret.value() instanceof InterpolationExpressionNode);
    }

    @Test
    @DisplayName("List literal expression")
    void listLiteral() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int x() { List<int> v = [40, 2]\n return v[0] + v[1] }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        VariableDeclarationNode decl = (VariableDeclarationNode) fn.body().statements().get(0);
        assertTrue(decl.initializer() instanceof ListLiteralExpressionNode);
    }

    @Test
    @DisplayName("Map literal expression")
    void mapLiteral() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int x() { Map<String, int> m = {\"a\": 40, \"b\": 2}\n return m[\"a\"] + m[\"b\"] }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        VariableDeclarationNode decl = (VariableDeclarationNode) fn.body().statements().get(0);
        assertTrue(decl.initializer() instanceof MapLiteralExpressionNode);
    }

    @Test
    @DisplayName("Member access expression")
    void memberAccess() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int x() { return \"hello\".length }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        ReturnStatementNode ret = (ReturnStatementNode) fn.body().statements().get(0);
        assertTrue(ret.value() instanceof MemberAccessExpressionNode);
    }

    @Test
    @DisplayName("Index access expression")
    void indexAccess() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int x() { List<int> v = [10, 20]\n return v[1] }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        ReturnStatementNode ret = (ReturnStatementNode) fn.body().statements().get(1);
        assertTrue(ret.value() instanceof IndexExpressionNode);
    }

    @Test
    @DisplayName("Named arguments in call")
    void namedArguments() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    int add(int a, int b) { return a + b }\n    int x() { return add(a = 40, b = 2) }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(1);
        ReturnStatementNode ret = (ReturnStatementNode) fn.body().statements().get(0);
        CallExpressionNode call = (CallExpressionNode) ret.value();
        assertTrue(call.arguments().get(0) instanceof NamedArgumentExpressionNode);
    }

    @Test
    @DisplayName("Boolean expression with && and !")
    void booleanExpression() {
        ScriptNode node = parseScript("@Script(name=\"T\", version=\"1\")\nscript T {\n    boolean x() { boolean e = true\n return e && !false }\n}");
        FunctionNode fn = (FunctionNode) node.members().get(0);
        ReturnStatementNode ret = (ReturnStatementNode) fn.body().statements().get(1);
        BinaryExpressionNode bin = (BinaryExpressionNode) ret.value();
        assertEquals("&&", bin.operator());
    }

    // === Multiple Members ===

    @Test
    @DisplayName("Script with multiple members")
    void multipleMembers() {
        ScriptNode node = parseScript("""
            @Script(name="T", version="1")
            script T {
                int x = 0
                static int total = 0
                #int MAX = 5
                int answer() { return 42 }
                void reset() { }
                entry onLoad() { }
            }
            """);
        assertEquals(6, node.members().size());
    }

    @Test
    @DisplayName("Full V2 canonical script parses")
    void fullCanonicalScript() {
        ScriptNode node = parseScript("""
            @Script(name = "Auto Miner", version = "1.0.0", author = "Impact")
            @Permissions(Permission.WORLD_READ, Permission.PLAYER_CONTROL)
            script AutoMiner {
                settings {
                    @Number radius ("R", 8..128, 1, 32, @Number.Slider)
                    @String target ("T", 1..50, "diamond_ore", @String.Small)
                    @Boolean avoidPlayers ("AP", true)
                }
                int ticks = 0
                static int totalMined = 0
                #int MAX_ATTEMPTS = 5
                entry onLoad() { }
                async event onTick(TickEvent e) { ticks++\n return false }
                int totalMined() { return totalMined }
                entry onUnload() { }
            }
            """);
        assertEquals("AutoMiner", node.scriptName());
        assertNotNull(node.settingBlock());
        assertEquals(3, node.settingBlock().declarations().size());
        assertTrue(node.members().size() >= 6);
    }

    // === V1 Rejection ===

    @Test
    @DisplayName("V1 'fun' keyword is rejected")
    void v1FunRejected() {
        ParseResult r = parse("@Script(name=\"T\", version=\"1\")\nscript T {\n    fun foo() { }\n}");
        assertTrue(r.hasErrors(), "V1 'fun' should produce parser error");
    }

    @Test
    @DisplayName("V1 'val' keyword is rejected")
    void v1ValRejected() {
        ParseResult r = parse("@Script(name=\"T\", version=\"1\")\nscript T {\n    val x = 5\n}");
        assertTrue(r.hasErrors(), "V1 'val' should produce parser error");
    }

    @Test
    @DisplayName("V1 'var' keyword is rejected")
    void v1VarRejected() {
        ParseResult r = parse("@Script(name=\"T\", version=\"1\")\nscript T {\n    var y = 0\n}");
        assertTrue(r.hasErrors(), "V1 'var' should produce parser error");
    }

    @Test
    @DisplayName("V1 'suspend' keyword is rejected")
    void v1SuspendRejected() {
        ParseResult r = parse("@Script(name=\"T\", version=\"1\")\nscript T {\n    suspend fun run() { }\n}");
        assertTrue(r.hasErrors(), "V1 'suspend' should produce parser error");
    }
}
