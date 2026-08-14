package io.velora.internal.ast;

/**
 * For loop: {@code for (block in blocks) { ... }}.
 */
public final class ForStatementNode extends StatementNode {
    private final TypeNode variableType;
    private final String variable;
    private final ExpressionNode iterable;
    private final BlockNode body;

    public ForStatementNode(String filePath, int line, int column, TypeNode variableType, String variable, ExpressionNode iterable, BlockNode body) {
        super(filePath, line, column);
        this.variableType = variableType;
        this.variable = variable;
        this.iterable = iterable;
        this.body = body;
    }

    public TypeNode variableType() { return variableType; }
    public String variable() { return variable; }
    public ExpressionNode iterable() { return iterable; }
    public BlockNode body() { return body; }

    @Override
    public String nodeName() { return "For"; }
}
