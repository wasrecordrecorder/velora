package io.velora.internal.ast;

public final class ExpressionStatementNode extends StatementNode {
    private final ExpressionNode expression;

    public ExpressionStatementNode(String filePath, int line, int column, ExpressionNode expression) {
        super(filePath, line, column);
        this.expression = expression;
    }

    public ExpressionNode expression() { return expression; }

    @Override
    public String nodeName() { return "ExprStmt"; }
}
