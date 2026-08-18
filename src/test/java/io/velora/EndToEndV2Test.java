package io.velora;

import io.velora.api.VeloraLimits;
import io.velora.api.compiler.Diagnostic;
import io.velora.api.function.*;
import io.velora.api.type.VeloraTypes;
import io.velora.internal.bytecode.*;
import io.velora.internal.ir.*;
import io.velora.internal.lexer.*;
import io.velora.internal.parser.*;
import io.velora.internal.runtime.*;
import io.velora.internal.registry.*;
import io.velora.internal.compiler.*;
import io.velora.internal.script.*;
import io.velora.internal.scheduler.*;
import io.velora.internal.semantic.*;
import io.velora.internal.setting.*;
import io.velora.internal.vm.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class EndToEndV2Test {

    private DefaultTypeRegistry typeRegistry;
    private DefaultSettingRegistry settingRegistry;
    private DefaultConstantRegistry constantRegistry;
    private DefaultApiRegistry apiRegistry;

    @BeforeEach
    void setUp() {
        typeRegistry = new DefaultTypeRegistry();
        settingRegistry = new DefaultSettingRegistry();
        constantRegistry = new DefaultConstantRegistry();
        apiRegistry = new DefaultApiRegistry(new DefaultTypeRegistry());
    }

    private CompiledModule compile(String source) {
        LexerResult lexResult = new Lexer(source, "main.vls").lex();
        assertTrue(lexResult.diagnostics().isEmpty(), "Lexer errors: " + lexResult.diagnostics());

        ParseResult parseResult = Parser.parse(source, "main.vls");
        assertTrue(parseResult.diagnostics().isEmpty(), "Parser errors: " + parseResult.diagnostics());
        assertNotNull(parseResult.scriptNode());

        SemanticAnalyzer analyzer = new SemanticAnalyzer(
                typeRegistry, settingRegistry, apiRegistry, constantRegistry);
        ResolvedScript resolved = analyzer.analyze(parseResult.scriptNode());
        assertTrue(analyzer.diagnostics().isEmpty(), "Semantic errors: " + analyzer.diagnostics());

        IrModule irModule = new IrBuilder(resolved, apiRegistry).build();
        assertTrue(new IrVerifier().verify(irModule).isEmpty(), "IR verifier errors");

        CompiledModule module = new BytecodeWriter().write(irModule);
        List<Diagnostic> verify = new BytecodeVerifier().verify(module);
        assertTrue(verify.stream().noneMatch(Diagnostic::isError), "Bytecode verifier errors: " + verify);

        return module;
    }

    private CompiledModule compileAllowErrors(String source) {
        ParseResult parseResult = Parser.parse(source, "main.vls");
        if (parseResult.scriptNode() == null) return null;
        SemanticAnalyzer analyzer = new SemanticAnalyzer(
                typeRegistry, settingRegistry, apiRegistry, constantRegistry);
        ResolvedScript resolved = analyzer.analyze(parseResult.scriptNode());
        if (!analyzer.diagnostics().isEmpty()) return null;
        IrModule irModule = new IrBuilder(resolved, apiRegistry).build();
        return new BytecodeWriter().write(irModule);
    }

    private ScriptValue execute(CompiledModule module, int funcIndex) {
        VirtualMachine vm = new VirtualMachine(apiRegistry, List.of(), 500_000);
        VmExecutionResult result = vm.execute(module, funcIndex, new ScriptValue[0]);
        assertTrue(result.success(), "VM failed: " + (result.error() != null ? result.error().message() : "unknown"));
        return result.returnValue();
    }

    private int asInt(ScriptValue v) {
        assertTrue(v instanceof PrimitiveValue.IntV, "Expected IntV, got " + v.getClass().getSimpleName());
        return ((PrimitiveValue.IntV) v).value();
    }

    private boolean asBool(ScriptValue v) {
        assertTrue(v instanceof PrimitiveValue.BooleanV, "Expected BooleanV, got " + v.getClass().getSimpleName());
        return ((PrimitiveValue.BooleanV) v).value();
    }

    private String asString(ScriptValue v) {
        assertTrue(v instanceof StringValue, "Expected StringValue, got " + v.getClass().getSimpleName());
        return ((StringValue) v).value();
    }

    // === Literals ===

    @Test
    @DisplayName("Integer literal return")
    void literalReturn() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return 42 }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Boolean literal return")
    void booleanReturn() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return true }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("String literal return")
    void stringReturn() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    String answer() { return \"hello\" }\n}");
        assertEquals("hello", asString(execute(m, 0)));
    }

    @Test
    @DisplayName("Zero return")
    void zeroReturn() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return 0 }\n}");
        assertEquals(0, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Negative number via arithmetic")
    void negativeNumber() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return 0 - 42 }\n}");
        assertEquals(-42, asInt(execute(m, 0)));
    }

    // === Arithmetic ===

    @Test
    @DisplayName("Long comparisons preserve integer precision")
    void longComparisonPrecision() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return 9007199254740992L < 9007199254740993L }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("Multiplication")
    void multiplication() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return 6 * 7 }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Addition")
    void addition() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return 40 + 2 }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Subtraction")
    void subtraction() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return 50 - 8 }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Division")
    void division() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return 84 / 2 }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Modulo")
    void modulo() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return 45 % 3 }\n}");
        assertEquals(0, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Complex arithmetic with precedence")
    void complexArithmetic() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return 2 + 3 * 4 - 1 }\n}");
        assertEquals(13, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Parenthesized arithmetic")
    void parenthesizedArithmetic() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return (2 + 3) * 4 }\n}");
        assertEquals(20, asInt(execute(m, 0)));
    }

    // === Locals ===

    @Test
    @DisplayName("Local variable declaration and return")
    void localVariable() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 42\n return x }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Multiple locals")
    void multipleLocals() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int a = 10\n int b = 20\n return a + b }\n}");
        assertEquals(30, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Local assignment after declaration")
    void localAssignment() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 10\n x = 42\n return x }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    // === Increment / Decrement ===

    @Test
    @DisplayName("Post-increment")
    void postIncrement() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 41\n x++\n return x }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Post-decrement")
    void postDecrement() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 43\n x--\n return x }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    // === Compound Assignment ===

    @Test
    @DisplayName("+= compound assignment")
    void plusEquals() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 40\n x += 2\n return x }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("-= compound assignment")
    void minusEquals() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 50\n x -= 8\n return x }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("*= compound assignment")
    void starEquals() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 6\n x *= 7\n return x }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("/= compound assignment")
    void slashEquals() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 84\n x /= 2\n return x }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("%= compound assignment")
    void percentEquals() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 100\n x %= 58\n return x }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    // === Control Flow ===

    @Test
    @DisplayName("If-true branch")
    void ifTrueBranch() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { if (true) { return 42 } return 0 }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("If-false branch with else")
    void ifFalseElseBranch() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { if (false) { return 0 } else { return 42 } }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("If with condition from comparison")
    void ifComparison() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 10\n if (x > 5) { return 42 } return 0 }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("While loop counting to 42")
    void whileLoop42() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 0\n while (x < 42) { x++ } return x }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("While loop with complex condition")
    void whileLoopComplex() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int sum = 0\n int i = 1\n while (i <= 10) { sum += i\n i++ } return sum }\n}");
        assertEquals(55, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("For-in loop summing list")
    void forInSum() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { List<int> v = [40, 2]\n int s = 0\n for (int x in v) { s += x } return s }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("For-in loop with larger list")
    void forInLarger() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { List<int> v = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]\n int s = 0\n for (int x in v) { s += x } return s }\n}");
        assertEquals(55, asInt(execute(m, 0)));
    }

    // === Function Calls ===

    @Test
    @DisplayName("User method call")
    void userMethodCall() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int add(int a, int b) { return a + b }\n    int answer() { return add(40, 2) }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(42, asInt(execute(m, fn.index())));
    }

    @Test
    @DisplayName("Nested function calls")
    void nestedCalls() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int double_it(int n) { return n + n }\n    int add(int a, int b) { return a + b }\n    int answer() { return double_it(add(10, 11)) }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(42, asInt(execute(m, fn.index())));
    }

    @Test
    @DisplayName("Default argument")
    void defaultArgument() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int add(int a, int b = 2) { return a + b }\n    int answer() { return add(40) }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(42, asInt(execute(m, fn.index())));
    }

    @Test
    @DisplayName("Named arguments")
    void namedArguments() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int add(int a, int b) { return a + b }\n    int answer() { return add(a = 40, b = 2) }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(42, asInt(execute(m, fn.index())));
    }

    @Test
    @DisplayName("Named arguments reversed order")
    void namedArgumentsReversed() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int sub(int a, int b) { return a - b }\n    int answer() { return sub(b = 8, a = 50) }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(42, asInt(execute(m, fn.index())));
    }

    // === Collections ===

    @Test
    @DisplayName("List index access")
    void listIndex() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { List<int> v = [40, 2]\n return v[0] + v[1] }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("List with 3 elements")
    void listThreeElements() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { List<int> v = [10, 20, 30]\n return v[0] + v[1] + v[2] }\n}");
        assertEquals(60, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Map index access")
    void mapIndex() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { Map<String, int> m = {\"a\": 40, \"b\": 2}\n return m[\"a\"] + m[\"b\"] }\n}");
        assertEquals(42, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("String .length member access")
    void stringLength() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return \"xxxxxx\".length }\n}");
        assertEquals(6, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("String .length with longer string")
    void stringLengthLonger() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return \"Hello, World!\".length }\n}");
        assertEquals(13, asInt(execute(m, 0)));
    }

    // === Interpolation ===

    @Test
    @DisplayName("String interpolation with variable")
    void interpolationVariable() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    String answer() { int v = 42\n return \"v${v}\" }\n}");
        assertEquals("v42", asString(execute(m, 0)));
    }

    @Test
    @DisplayName("String interpolation with expression")
    void interpolationExpression() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    String answer() { return \"${6 * 7}\" }\n}");
        assertEquals("42", asString(execute(m, 0)));
    }

    @Test
    @DisplayName("String interpolation rejects trailing expression tokens")
    void interpolationRejectsTrailingTokens() {
        ParseResult result = Parser.parse("@Script(\"T\")\nscript T { String answer() { return \"${1 2}\" } }", "main.vls");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code() == io.velora.api.compiler.DiagnosticCode.PARSER_UNEXPECTED_TOKEN));
    }

    @Test
    @DisplayName("String interpolation ignores braces inside nested strings")
    void interpolationNestedStringBrace() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    String answer() { return \"${\"}\"}\" }\n}");
        assertEquals("}", asString(execute(m, 0)));
    }

    @Test
    @DisplayName("String interpolation with multiple segments")
    void interpolationMultiple() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    String answer() { int a = 1\n int b = 2\n return \"a=${a},b=${b}\" }\n}");
        assertEquals("a=1,b=2", asString(execute(m, 0)));
    }

    // === Boolean Logic ===

    @Test
    @DisplayName("AND operator")
    void andOperator() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return true && true }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("AND short-circuit: false && anything = false")
    void andShortCircuit() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return false && true }\n}");
        assertFalse(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("OR operator")
    void orOperator() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return false || true }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("NOT operator")
    void notOperator() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return !false }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("Complex boolean: true && !false")
    void complexBoolean() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { boolean e = true\n return e && !false }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    // === Comparisons ===

    @Test
    @DisplayName("Equal comparison")
    void equalComparison() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return 42 == 42 }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("Not equal comparison")
    void notEqualComparison() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return 42 != 0 }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("Less than comparison")
    void lessThan() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return 5 < 10 }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("Greater than comparison")
    void greaterThan() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return 10 > 5 }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("Less than or equal")
    void lessEqual() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return 10 <= 10 }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("Greater than or equal")
    void greaterEqual() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { return 10 >= 10 }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    // === Constants and Static Fields ===

    @Test
    @DisplayName("Const field access")
    void constField() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    #int ANSWER = 42\n    int answer() { return ANSWER }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(42, asInt(execute(m, fn.index())));
    }

    @Test
    @DisplayName("Static field access")
    void staticField() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    static int value = 42\n    int answer() { return value }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(42, asInt(execute(m, fn.index())));
    }

    @Test
    @DisplayName("Static field increment")
    void staticFieldIncrement() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    static int counter = 0\n    int answer() { counter++\n counter++\n return counter }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(2, asInt(execute(m, fn.index())));
    }

    @Test
    @DisplayName("Static field compound assignment")
    void staticFieldCompound() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    static int total = 0\n    int answer() { total += 42\n return total }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(42, asInt(execute(m, fn.index())));
    }

    // === String Concatenation ===

    @Test
    @DisplayName("String concatenation with +")
    void stringConcat() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    String answer() { return \"Hello\" + \" \" + \"World\" }\n}");
        assertEquals("Hello World", asString(execute(m, 0)));
    }

    @Test
    @DisplayName("String + int concatenation")
    void stringIntConcat() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    String answer() { return \"n=\" + 42 }\n}");
        assertEquals("n=42", asString(execute(m, 0)));
    }

    // === Null Handling ===

    @Test
    @DisplayName("Null comparison with ==")
    void nullEqualNull() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { String a = null\n return a == null }\n}");
        assertTrue(asBool(execute(m, 0)));
    }

    @Test
    @DisplayName("Null comparison with !=")
    void nullNotEqualNull() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    boolean answer() { String a = null\n return a != null }\n}");
        assertFalse(asBool(execute(m, 0)));
    }

    // === API Calls ===

    @Test
    @DisplayName("API function call returns value")
    void apiCall() {
        apiRegistry.namespace("test", ns -> ns.function("answer", VeloraTypes.INT, ctx -> 42));
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return test.answer() }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(42, asInt(execute(m, fn.index())));
    }

    @Test
    @DisplayName("API function with arguments")
    void apiCallWithArgs() {
        apiRegistry.namespace("math", ns -> ns.function("add", VeloraTypes.INT, p -> {
            p.required("left", VeloraTypes.INT);
            p.required("right", VeloraTypes.INT);
        }, ctx -> ((Number) ctx.argument(0)).intValue() + ((Number) ctx.argument(1)).intValue()));
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { return math.add(20, 22) }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(42, asInt(execute(m, fn.index())));
    }

    // === Instruction Limit ===

    @Test
    @DisplayName("Instruction limit exceeded returns failure")
    void instructionLimitExceeded() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int x = 0\n while (true) { x++ } return x }\n}");
        VirtualMachine vm = new VirtualMachine(apiRegistry, List.of(), 1000);
        VmExecutionResult result = vm.execute(m, 0, new ScriptValue[0]);
        assertFalse(result.success(), "Should fail with instruction limit exceeded");
        assertNotNull(result.error());
    }

    // === Deep Recursion ===

    @Test
    @DisplayName("Recursive function: factorial")
    void recursiveFactorial() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int fact(int n) { if (n <= 1) { return 1 } return n * fact(n - 1) }\n    int answer() { return fact(5) }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(120, asInt(execute(m, fn.index())));
    }

    @Test
    @DisplayName("Recursive function: fibonacci")
    void recursiveFibonacci() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int fib(int n) { if (n < 2) { return n } return fib(n - 1) + fib(n - 2) }\n    int answer() { return fib(10) }\n}");
        CompiledFunction fn = m.functionByName("answer");
        assertEquals(55, asInt(execute(m, fn.index())));
    }

    // === Scheduler: Delay ===

    @Test
    @DisplayName("Delay through scheduler")
    void delayThroughScheduler() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    async run() { delay(1000000000) }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        Map<String, CompiledModule> modules = Map.of("T", m);
        Map<String, List<io.velora.api.setting.SettingDescriptor>> settings = Map.of("T", List.of());

        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        long now = System.nanoTime();
        scheduler.tick(now, modules, settings);
        assertEquals(FiberState.SLEEPING, fiber.state(), "Fiber should be sleeping after delay");

        scheduler.tick(now + 2_000_000_000L, modules, settings);
        assertTrue(fiber.isDone(), "Fiber should be done after waking");
    }

    // === Scheduler: Spawn + Await ===

    @Test
    @DisplayName("Spawn and await child fiber")
    void spawnAndAwait() {
        CompiledModule m = compile("""
            @Script("T")
@Version("1")
            script T {
                int child() { return 42 }
                async int run() { Task<int> r = spawn child()
                    return await(r) }
            }
            """);
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        Map<String, CompiledModule> modules = Map.of("T", m);
        Map<String, List<io.velora.api.setting.SettingDescriptor>> settings = Map.of("T", List.of());

        CompiledFunction runFn = m.functionByName("run");
        ScriptFiber parent = scheduler.spawnFiber("T", runFn.index(), new ScriptValue[0]);
        long now = System.nanoTime();

        scheduler.tick(now, modules, settings);
        assertEquals(FiberState.WAITING_TASK, parent.state(), "Parent should be waiting for child");

        scheduler.tick(now + 1, modules, settings);
        assertTrue(parent.isDone(), "Parent should be done after child completes");
    }

    // === Scheduler: Cancellation ===

    @Test
    @DisplayName("Fiber cancellation")
    void fiberCancellation() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    async run() { delay(999999999999) }\n}");
        ScriptScheduler scheduler = new ScriptScheduler(VeloraLimits.defaults(), apiRegistry);
        Map<String, CompiledModule> modules = Map.of("T", m);
        Map<String, List<io.velora.api.setting.SettingDescriptor>> settings = Map.of("T", List.of());

        ScriptFiber fiber = scheduler.spawnFiber("T", 0, new ScriptValue[0]);
        long now = System.nanoTime();
        scheduler.tick(now, modules, settings);
        assertEquals(FiberState.SLEEPING, fiber.state());

        scheduler.cancelFiber(fiber.id());
        scheduler.tick(now + 1, modules, settings);
        assertTrue(fiber.isDone(), "Fiber should be done after cancellation");
    }

    // === Edge Cases ===

    @Test
    @DisplayName("Empty method body returns null")
    void emptyMethodBody() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    noop() { }\n}");
        VirtualMachine vm = new VirtualMachine(apiRegistry, List.of(), 10000);
        VmExecutionResult result = vm.execute(m, 0, new ScriptValue[0]);
        assertTrue(result.success(), "Empty void method should succeed");
    }

    @Test
    @DisplayName("Method with multiple return paths")
    void multipleReturns() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer(int n) { if (n > 0) { return 42 } return 0 }\n}");
        CompiledFunction fn = m.functionByName("answer");
        VirtualMachine vm = new VirtualMachine(apiRegistry, List.of(), 10000);
        VmExecutionResult result = vm.execute(m, fn.index(), new ScriptValue[]{PrimitiveValue.of(1)});
        assertTrue(result.success());
        assertEquals(42, asInt(result.returnValue()));
    }

    @Test
    @DisplayName("Large loop: sum 1 to 100")
    void largeLoop() {
        CompiledModule m = compile("@Script(\"T\")\n@Version(\"1\")\nscript T {\n    int answer() { int s = 0\n int i = 1\n while (i <= 100) { s += i\n i++ } return s }\n}");
        assertEquals(5050, asInt(execute(m, 0)));
    }

    @Test
    @DisplayName("Nested if-else")
    void nestedIfElse() {
        CompiledModule m = compile("""
            @Script("T")
@Version("1")
            script T {
                int answer(int n) {
                    if (n > 10) {
                        if (n > 20) { return 30 }
                        return 15
                    } else {
                        return 5
                    }
                }
            }
            """);
        CompiledFunction fn = m.functionByName("answer");
        VirtualMachine vm = new VirtualMachine(apiRegistry, List.of(), 10000);
        VmExecutionResult r1 = vm.execute(m, fn.index(), new ScriptValue[]{PrimitiveValue.of(25)});
        assertEquals(30, asInt(r1.returnValue()));
        VmExecutionResult r2 = vm.execute(m, fn.index(), new ScriptValue[]{PrimitiveValue.of(15)});
        assertEquals(15, asInt(r2.returnValue()));
        VmExecutionResult r3 = vm.execute(m, fn.index(), new ScriptValue[]{PrimitiveValue.of(5)});
        assertEquals(5, asInt(r3.returnValue()));
    }
}
