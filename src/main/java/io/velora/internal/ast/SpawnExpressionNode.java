package io.velora.internal.ast;

import java.util.List;

/**
 * Spawn a child fiber: {@code spawn monitorPlayers()}.
 */
public final class SpawnExpressionNode extends ExpressionNode {
    private final ExpressionNode callee;
    private final List<ExpressionNode> arguments;

    public SpawnExpressionNode(String filePath, int line, int column, ExpressionNode callee, List<ExpressionNode> arguments) {
        super(filePath, line, column);
        this.callee = callee;
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public ExpressionNode callee() { return callee; }
    public List<ExpressionNode> arguments() { return arguments; }

    @Override
    public String nodeName() { return "Spawn"; }
}
