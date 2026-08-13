package io.velora.internal.ast;

/**
 * Type check: {@code block is BlockRef}. Yields a Boolean.
 */
public final class IsExpressionNode extends ExpressionNode {
    private final ExpressionNode operand;
    private final TypeNode type;

    public IsExpressionNode(String filePath, int line, int column, ExpressionNode operand, TypeNode type) {
        super(filePath, line, column);
        this.operand = operand;
        this.type = type;
    }

    public ExpressionNode operand() { return operand; }
    public TypeNode type() { return type; }

    @Override
    public String nodeName() { return "Is"; }
}
