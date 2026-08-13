package io.velora.internal.language;

import io.velora.api.language.TextEdit;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

public final class RenameEngine {
    private RenameEngine() {}

    public static List<TextEdit> rename(String content, String oldName, String newName, String filePath) {
        if (oldName == null || oldName.isEmpty() || newName == null || newName.isEmpty() || !Character.isJavaIdentifierStart(newName.charAt(0))) return List.of();
        for (int i = 1; i < newName.length(); i++) if (!Character.isJavaIdentifierPart(newName.charAt(i))) return List.of();
        List<TextEdit> edits = new ArrayList<>();
        for (Token token : new Lexer(content, filePath).lex().tokens()) {
            if (token.is(TokenType.IDENTIFIER) && token.text().equals(oldName)) {
                edits.add(TextEdit.replace(filePath, token.line(), token.column() + 1, token.length(), newName));
            }
        }
        return List.copyOf(edits);
    }
}
