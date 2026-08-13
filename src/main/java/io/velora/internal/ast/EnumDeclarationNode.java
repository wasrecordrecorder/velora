package io.velora.internal.ast;

import java.util.List;

/**
 * An enum declaration inside a script, e.g.
 * {@code enum MiningMode { NEAREST, SAFEST, HIGHEST }}.
 */
public final class EnumDeclarationNode extends ScriptMemberNode {
    private final String name;
    private final List<String> constants;
    private final boolean isPrivate;

    public EnumDeclarationNode(String filePath, int line, int column,
                               String name, List<String> constants, boolean isPrivate) {
        super(filePath, line, column);
        this.name = name;
        this.constants = constants == null ? List.of() : List.copyOf(constants);
        this.isPrivate = isPrivate;
    }

    public String name() { return name; }
    public List<String> constants() { return constants; }
    public boolean isPrivate() { return isPrivate; }

    @Override
    public String nodeName() { return "Enum:" + name; }
}
