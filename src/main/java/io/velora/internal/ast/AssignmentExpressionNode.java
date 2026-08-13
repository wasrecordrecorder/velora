package io.velora.internal.ast;

public final class AssignmentExpressionNode extends ExpressionNode {
    private final ExpressionNode target;
    private final String operator;
    private final ExpressionNode value;

    public AssignmentExpressionNode(String filePath, int line, int column,
                                    ExpressionNode target, String operator, ExpressionNode value) {
        super(filePath, line, column);
        this.target = target;
        this.operator = operator;
        this.value = value;
    }

    public ExpressionNode target() { return target; }
    public String operator() { return operator; }
    public ExpressionNode value() { return value; }

    @Override
    public String nodeName() { return "Assign:" + operator; }
}
