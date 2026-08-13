package io.velora.internal.ast;

// ScriptMemberNode extends StatementNode — parser controls which node types appear
// at script-member level vs statement level, preventing the semantic blur by the
// parser rather than the type hierarchy.
public abstract class ScriptMemberNode extends StatementNode {
    protected ScriptMemberNode(String filePath, int line, int column) {
        super(filePath, line, column);
    }
}
