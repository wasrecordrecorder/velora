package io.velora.internal.ast;

public abstract class ExpressionNode extends AstNode {
    protected ExpressionNode(String filePath, int line, int column) {
        super(filePath, line, column);
    }
}
