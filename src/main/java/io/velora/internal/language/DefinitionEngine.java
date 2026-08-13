package io.velora.internal.language;

import io.velora.api.language.DefinitionLocation;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DefinitionEngine {
    private DefinitionEngine() {}

    public static Optional<DefinitionLocation> getDefinition(String content, int line, int column, String filePath) {
        if (line < 1 || column < 1) return Optional.empty();
        List<Token> tokens = significant(new Lexer(content, filePath).lex().tokens());
        Token target = tokenAt(tokens, line, column);
        if (target == null || target.type() != TokenType.IDENTIFIER) return Optional.empty();

        Token best = null;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.offset() >= target.offset()) break;
            if (!token.is(TokenType.IDENTIFIER) || !token.text().equals(target.text())) continue;
            if (isFunctionDeclaration(tokens, i) || isValueDeclaration(tokens, i)) best = token;
        }
        if (best == null && isFunctionDeclaration(tokens, tokens.indexOf(target))) best = target;
        return best == null ? Optional.empty() : Optional.of(DefinitionLocation.of(filePath, best.line(), best.column() + 1));
    }

    private static List<Token> significant(List<Token> source) {
        List<Token> result = new ArrayList<>();
        for (Token token : source) if (!token.isTrivia() && !token.is(TokenType.EOF)) result.add(token);
        return result;
    }

    private static Token tokenAt(List<Token> tokens, int line, int column) {
        int zeroColumn = column - 1;
        for (Token token : tokens) {
            if (token.line() != line || token.length() == 0) continue;
            if (zeroColumn >= token.column() && zeroColumn <= token.column() + token.length()) return token;
        }
        return null;
    }

    private static boolean isFunctionDeclaration(List<Token> tokens, int index) {
        if (index < 0 || index + 1 >= tokens.size() || !tokens.get(index + 1).is(TokenType.LPAREN)) return false;
        int depth = 0;
        for (int i = index + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.is(TokenType.LPAREN)) depth++;
            else if (token.is(TokenType.RPAREN) && --depth == 0) {
                return i + 1 < tokens.size() && tokens.get(i + 1).is(TokenType.LBRACE) && hasDeclarationPrefix(tokens, index);
            }
        }
        return false;
    }

    private static boolean isValueDeclaration(List<Token> tokens, int index) {
        if (index <= 0 || index + 1 >= tokens.size()) return false;
        Token next = tokens.get(index + 1);
        if (next.is(TokenType.LPAREN) || next.is(TokenType.DOT)) return false;
        return hasDeclarationPrefix(tokens, index);
    }

    private static boolean hasDeclarationPrefix(List<Token> tokens, int index) {
        if (index <= 0) return false;
        Token previous = tokens.get(index - 1);
        if (previous.is(TokenType.IDENTIFIER) || previous.is(TokenType.KW_VOID) || previous.is(TokenType.GT)) return true;
        if (previous.is(TokenType.HASH) && index > 1) {
            Token type = tokens.get(index - 2);
            return type.is(TokenType.IDENTIFIER) || type.is(TokenType.GT);
        }
        return false;
    }
}
