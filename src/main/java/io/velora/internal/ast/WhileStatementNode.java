package io.velora.internal.ast;

public final class WhileStatementNode extends StatementNode {
    private final ExpressionNode condition;
    private final BlockNode body;

    public WhileStatementNode(String filePath, int line, int column,
                              ExpressionNode condition, BlockNode body) {
        super(filePath, line, column);
        this.condition = condition;
        this.body = body;
    }

    public ExpressionNode condition() { return condition; }
    public BlockNode body() { return body; }

    @Override
    public String nodeName() { return "While"; }
}
