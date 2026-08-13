package io.velora.internal.ast;

/**
 * Qualified constant/enum access: {@code Blocks.DIAMOND_ORE}, {@code MiningMode.NEAREST}.
 * Resolved at semantic time against the constant and type registries.
 */
public final class QualifiedExpressionNode extends ExpressionNode {
    private final String qualifier;
    private final String member;

    public QualifiedExpressionNode(String filePath, int line, int column, String qualifier, String member) {
        super(filePath, line, column);
        this.qualifier = qualifier;
        this.member = member;
    }

    public String qualifier() { return qualifier; }
    public String member() { return member; }

    @Override
    public String nodeName() { return "Qualified:" + qualifier + "." + member; }
}
