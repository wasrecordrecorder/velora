package io.velora.internal.ast;

import java.util.List;

public final class BlockNode extends AstNode {
    private final List<StatementNode> statements;

    public BlockNode(String filePath, int line, int column, List<StatementNode> statements) {
        super(filePath, line, column);
        this.statements = statements == null ? List.of() : List.copyOf(statements);
    }

    public List<StatementNode> statements() { return statements; }

    @Override
    public String nodeName() { return "Block"; }
}
