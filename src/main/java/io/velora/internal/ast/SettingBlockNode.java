package io.velora.internal.ast;

import java.util.List;

public final class SettingBlockNode extends ScriptMemberNode {
    private final List<SettingDeclarationNode> declarations;

    public SettingBlockNode(String filePath, int line, int column, List<SettingDeclarationNode> declarations) {
        super(filePath, line, column);
        this.declarations = declarations == null ? List.of() : List.copyOf(declarations);
    }

    public List<SettingDeclarationNode> declarations() { return declarations; }

    @Override
    public String nodeName() { return "SettingBlock"; }
}
