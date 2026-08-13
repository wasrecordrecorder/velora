package io.velora.internal.ast;

/**
 * Elvis operator: {@code left ?: right}. Yields left if non-null, else right.
 */
public final class ElvisExpressionNode extends ExpressionNode {
    private final ExpressionNode left;
    private final ExpressionNode right;

    public ElvisExpressionNode(String filePath, int line, int column, ExpressionNode left, ExpressionNode right) {
        super(filePath, line, column);
        this.left = left;
        this.right = right;
    }

    public ExpressionNode left() { return left; }
    public ExpressionNode right() { return right; }

    @Override
    public String nodeName() { return "Elvis"; }
}
