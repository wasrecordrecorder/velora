package io.velora.internal.ast;

public final class BinaryExpressionNode extends ExpressionNode {
    private final ExpressionNode left;
    private final String operator;
    private final ExpressionNode right;

    public BinaryExpressionNode(String filePath, int line, int column,
                                 ExpressionNode left, String operator, ExpressionNode right) {
        super(filePath, line, column);
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    public ExpressionNode left() { return left; }
    public String operator() { return operator; }
    public ExpressionNode right() { return right; }

    @Override
    public String nodeName() { return "Binary:" + operator; }
}
