package io.velora.internal.ast;

public final class ImportNode extends AstNode {
    private final String importName;
    private final String alias;

    public ImportNode(String filePath, int line, int column, String importName, String alias) {
        super(filePath, line, column);
        this.importName = importName;
        this.alias = alias;
    }

    public String importName() { return importName; }
    public String alias() { return alias; }

    @Override
    public String nodeName() { return "Import"; }
}
