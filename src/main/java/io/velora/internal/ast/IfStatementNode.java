package io.velora.internal.ast;

public final class IfStatementNode extends StatementNode {
    private final ExpressionNode condition;
    private final BlockNode thenBlock;
    private final BlockNode elseBlock;

    public IfStatementNode(String filePath, int line, int column,
                           ExpressionNode condition, BlockNode thenBlock, BlockNode elseBlock) {
        super(filePath, line, column);
        this.condition = condition;
        this.thenBlock = thenBlock;
        this.elseBlock = elseBlock;
    }

    public ExpressionNode condition() { return condition; }
    public BlockNode thenBlock() { return thenBlock; }
    public BlockNode elseBlock() { return elseBlock; }

    @Override
    public String nodeName() { return "If"; }
}
