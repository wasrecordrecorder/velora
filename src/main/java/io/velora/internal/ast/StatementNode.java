package io.velora.internal.ast;

public abstract class StatementNode extends AstNode {
    protected StatementNode(String filePath, int line, int column) {
        super(filePath, line, column);
    }
}
