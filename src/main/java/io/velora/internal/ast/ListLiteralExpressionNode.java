package io.velora.internal.ast;

import java.util.List;

public final class ListLiteralExpressionNode extends ExpressionNode {
    private final List<ExpressionNode> elements;

    public ListLiteralExpressionNode(String filePath, int line, int column, List<ExpressionNode> elements) {
        super(filePath, line, column);
        this.elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public List<ExpressionNode> elements() { return elements; }

    @Override
    public String nodeName() { return "ListLiteral"; }
}
