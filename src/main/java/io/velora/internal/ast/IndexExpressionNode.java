package io.velora.internal.ast;

/**
 * Index access: {@code list[0]} or {@code map[key]}.
 */
public final class IndexExpressionNode extends ExpressionNode {
    private final ExpressionNode receiver;
    private final ExpressionNode index;

    public IndexExpressionNode(String filePath, int line, int column, ExpressionNode receiver, ExpressionNode index) {
        super(filePath, line, column);
        this.receiver = receiver;
        this.index = index;
    }

    public ExpressionNode receiver() { return receiver; }
    public ExpressionNode index() { return index; }

    @Override
    public String nodeName() { return "Index"; }
}
