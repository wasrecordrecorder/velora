package io.velora.internal.ast;

public final class ReturnStatementNode extends StatementNode {
    private final ExpressionNode value;

    public ReturnStatementNode(String filePath, int line, int column, ExpressionNode value) {
        super(filePath, line, column);
        this.value = value;
    }

    public ExpressionNode value() { return value; }

    @Override
    public String nodeName() { return "Return"; }
}
