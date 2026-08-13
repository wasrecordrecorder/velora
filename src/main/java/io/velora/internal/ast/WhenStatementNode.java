package io.velora.internal.ast;

import java.util.List;

/**
 * When expression/statement: {@code when (mode) { NEAREST -> ... else -> ... }}.
 */
public final class WhenStatementNode extends StatementNode {
    private final ExpressionNode subject;
    private final List<Case> cases;
    private final BlockNode elseBody;

    public WhenStatementNode(String filePath, int line, int column, ExpressionNode subject, List<Case> cases, BlockNode elseBody) {
        super(filePath, line, column);
        this.subject = subject;
        this.cases = cases == null ? List.of() : List.copyOf(cases);
        this.elseBody = elseBody;
    }

    public ExpressionNode subject() { return subject; }
    public List<Case> cases() { return cases; }
    public BlockNode elseBody() { return elseBody; }

    @Override
    public String nodeName() { return "When"; }

    public record Case(List<ExpressionNode> conditions, BlockNode body) {}
}
