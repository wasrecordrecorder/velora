package io.velora.internal.ast;

import java.util.List;

/**
 * String interpolation: {@code "Target: ${target.name}"}. Segments are either
 * literal text or embedded expressions.
 */
public final class InterpolationExpressionNode extends ExpressionNode {
    private final List<Segment> segments;

    public InterpolationExpressionNode(String filePath, int line, int column, List<Segment> segments) {
        super(filePath, line, column);
        this.segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public List<Segment> segments() { return segments; }

    @Override
    public String nodeName() { return "Interpolation"; }

    public sealed interface Segment permits Text, Expr {}
    public record Text(String value) implements Segment {}
    public record Expr(ExpressionNode expression) implements Segment {}
}
