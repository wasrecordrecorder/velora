package io.velora.internal.ast;

public abstract class AstNode {
    private final String filePath;
    private final int line;
    private final int column;

    protected AstNode(String filePath, int line, int column) {
        this.filePath = filePath;
        this.line = line;
        this.column = column;
    }

    public String filePath() { return filePath; }
    public int line() { return line; }
    public int column() { return column; }

    public abstract String nodeName();
}
