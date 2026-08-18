package io.velora.internal.language;

import io.velora.api.language.DefinitionLocation;
import io.velora.api.language.TextEdit;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RenameEngine {
    private RenameEngine() {}

    public static List<TextEdit> rename(String content, String oldName, String newName, String filePath) {
        if (oldName == null || oldName.isEmpty() || newName == null || newName.isEmpty() || oldName.equals(newName)) return List.of();
        List<Token> nameTokens = new Lexer(newName, filePath).lex().tokens().stream().filter(token -> !token.isTrivia() && !token.is(TokenType.EOF)).toList();
        if (nameTokens.size() != 1 || !nameTokens.get(0).is(TokenType.IDENTIFIER) || !nameTokens.get(0).text().equals(newName)) return List.of();

        List<Token> occurrences = new ArrayList<>();
        Set<DefinitionLocation> definitions = new LinkedHashSet<>();
        for (Token token : new Lexer(content, filePath).lex().tokens()) {
            if (!token.is(TokenType.IDENTIFIER) || !token.text().equals(oldName)) continue;
            occurrences.add(token);
            DefinitionEngine.getDefinition(content, token.line(), token.column() + 1, filePath).ifPresent(definitions::add);
        }
        if (definitions.size() != 1) return List.of();
        DefinitionLocation target = definitions.iterator().next();
        List<TextEdit> edits = new ArrayList<>();
        for (Token token : occurrences) {
            if (DefinitionEngine.getDefinition(content, token.line(), token.column() + 1, filePath).filter(target::equals).isPresent()) {
                edits.add(TextEdit.replace(filePath, token.line(), token.column() + 1, token.length(), newName));
            }
        }
        return List.copyOf(edits);
    }
}
