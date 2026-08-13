package io.velora.internal.ast;

/**
 * Duration literal: {@code 1.tick}, {@code 50.milliseconds}, {@code 2.seconds}.
 * The amount is an expression (usually an integer literal); the unit selects the
 * conversion to {@link java.time.Duration}.
 */
public final class DurationExpressionNode extends ExpressionNode {
    private final ExpressionNode amount;
    private final String unit;

    public DurationExpressionNode(String filePath, int line, int column, ExpressionNode amount, String unit) {
        super(filePath, line, column);
        this.amount = amount;
        this.unit = unit;
    }

    public ExpressionNode amount() { return amount; }
    public String unit() { return unit; }

    @Override
    public String nodeName() { return "Duration:" + unit; }
}
