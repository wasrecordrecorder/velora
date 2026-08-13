package io.velora.internal.ast;

public final class IdentifierExpressionNode extends ExpressionNode {
    private final String name;

    public IdentifierExpressionNode(String filePath, int line, int column, String name) {
        super(filePath, line, column);
        this.name = name;
    }

    public String name() { return name; }

    @Override
    public String nodeName() { return "Identifier:" + name; }
}
