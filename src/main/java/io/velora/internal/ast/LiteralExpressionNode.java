package io.velora.internal.ast;

public final class LiteralExpressionNode extends ExpressionNode {
    private final Object value;
    private final LiteralKind kind;

    public LiteralExpressionNode(String filePath, int line, int column, Object value, LiteralKind kind) {
        super(filePath, line, column);
        this.value = value;
        this.kind = kind;
    }

    public Object value() { return value; }
    public LiteralKind kind() { return kind; }

    public enum LiteralKind { INTEGER, LONG, FLOAT, DOUBLE, STRING, BOOLEAN, NULL }

    @Override
    public String nodeName() { return "Literal:" + kind; }
}
