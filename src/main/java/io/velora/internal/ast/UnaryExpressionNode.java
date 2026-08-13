package io.velora.internal.ast;

public final class UnaryExpressionNode extends ExpressionNode {
    private final String operator;
    private final ExpressionNode operand;
    private final boolean prefix;

    public UnaryExpressionNode(String filePath, int line, int column,
                                String operator, ExpressionNode operand, boolean prefix) {
        super(filePath, line, column);
        this.operator = operator;
        this.operand = operand;
        this.prefix = prefix;
    }

    public String operator() { return operator; }
    public ExpressionNode operand() { return operand; }
    public boolean isPrefix() { return prefix; }

    @Override
    public String nodeName() { return "Unary:" + operator; }
}
