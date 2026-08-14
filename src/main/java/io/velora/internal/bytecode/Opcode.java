package io.velora.internal.bytecode;

/**
 * Bytecode opcodes for the Velora stack VM.
 *
 * <p>Each opcode has a fixed operand width (in 32-bit words) declared by
 * {@link #operandWords()}. The verifier and writer rely on this.
 */
public enum Opcode {
    // Constants
    CONST(1),          // CONST constIndex  -> push constant
    NULL(0),
    TRUE(0),
    FALSE(0),

    // Locals / fields / settings
    LOAD_LOCAL(1),     // LOAD_LOCAL index
    STORE_LOCAL(1),
    LOAD_FIELD(1),     // LOAD_FIELD fieldIndex
    STORE_FIELD(1),
    LOAD_STATIC(1),    // LOAD_STATIC staticIndex
    STORE_STATIC(1),
    LOAD_SETTING(1),   // LOAD_SETTING settingIndex

    // Stack
    POP(0),
    DUP(0),

    // Arithmetic
    ADD(0),
    SUB(0),
    MUL(0),
    DIV(0),
    MOD(0),
    NEGATE(0),

    // Comparison / logic
    EQUAL(0),
    NOT_EQUAL(0),
    LESS(0),
    LESS_EQUAL(0),
    GREATER(0),
    GREATER_EQUAL(0),
    NOT(0),
    IS_NULL(0),

    // Control flow
    JUMP(1),           // JUMP target
    JUMP_IF_FALSE(1),
    JUMP_IF_TRUE(1),
    LOOP(1),           // LOOP target (forced-yield aware)
    RETURN(0),

    // Calls
    CALL(2),           // CALL functionIndex argCount
    CALL_API(2),       // CALL_API apiIndex argCount
    CALL_SUSPEND(2),   // CALL_SUSPEND apiIndex argCount (returns a task)
    CALL_MEMBER(2),    // CALL_MEMBER memberIndex argCount
    GET_MEMBER(1),     // GET_MEMBER memberIndex (struct property)
    SET_MEMBER(1),

    // Collections
    CREATE_LIST(1),    // CREATE_LIST elementCount
    CREATE_SET(1),     // CREATE_SET elementCount
    CREATE_MAP(1),     // CREATE_MAP entryCount
    GET_INDEX(0),

    // Async
    SPAWN(2),          // SPAWN functionIndex argCount
    AWAIT(0),          // await top-of-stack task
    DELAY(0),          // delay by top-of-stack duration
    YIELD(0),          // forced yield
    CHECK_CANCELLED(0),

    // Debug
    LINE(1),           // LINE lineNumber
    BREAKPOINT(0),
    IS_TYPE(1),
    LOAD_QUALIFIED(2);

    private final int operandWords;

    Opcode(int operandWords) {
        this.operandWords = operandWords;
    }

    public int operandWords() {
        return operandWords;
    }

    public int instructionWords() {
        return 1 + operandWords;
    }
}
