package io.velora.internal.ast;

public final class MemberAccessExpressionNode extends ExpressionNode {
    private final ExpressionNode target;
    private final String member;
    private final boolean safeAccess;

    public MemberAccessExpressionNode(String filePath, int line, int column,
                                     ExpressionNode target, String member, boolean safeAccess) {
        super(filePath, line, column);
        this.target = target;
        this.member = member;
        this.safeAccess = safeAccess;
    }

    public ExpressionNode target() { return target; }
    public String member() { return member; }
    public boolean isSafeAccess() { return safeAccess; }

    @Override
    public String nodeName() { return "Member:" + member; }
}
