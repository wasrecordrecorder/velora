package io.velora.internal.ast;

import java.util.List;
import java.util.Map;

/**
 * Map literal: {@code {"a" to 1, "b" to 2}}.
 */
public final class MapLiteralExpressionNode extends ExpressionNode {
    private final List<Map.Entry<ExpressionNode, ExpressionNode>> entries;

    public MapLiteralExpressionNode(String filePath, int line, int column, List<Map.Entry<ExpressionNode, ExpressionNode>> entries) {
        super(filePath, line, column);
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public List<Map.Entry<ExpressionNode, ExpressionNode>> entries() { return entries; }

    @Override
    public String nodeName() { return "MapLiteral"; }
}
