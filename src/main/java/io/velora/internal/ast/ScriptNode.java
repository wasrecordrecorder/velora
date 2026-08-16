package io.velora.internal.ast;

import java.util.List;

public final class ScriptNode extends AstNode {
    private final List<ImportNode> imports;
    private final List<AnnotationNode> annotations;
    private final String scriptName;
    private final SettingBlockNode settingBlock;
    private final List<ScriptMemberNode> members;

    public ScriptNode(String filePath, int line, int column,
                      List<ImportNode> imports, List<AnnotationNode> annotations, String scriptName,
                      SettingBlockNode settingBlock, List<ScriptMemberNode> members) {
        super(filePath, line, column);
        this.imports = imports == null ? List.of() : List.copyOf(imports);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.scriptName = scriptName;
        this.settingBlock = settingBlock;
        this.members = members == null ? List.of() : List.copyOf(members);
    }

    public List<ImportNode> imports() { return imports; }
    public List<AnnotationNode> annotations() { return annotations; }
    public String scriptName() { return scriptName; }
    public SettingBlockNode settingBlock() { return settingBlock; }
    public List<ScriptMemberNode> members() { return members; }

    @Override
    public String nodeName() { return "Script"; }
}
