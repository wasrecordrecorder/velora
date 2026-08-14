package io.velora.internal.language;

import io.velora.api.language.DefinitionLocation;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.*;

public final class DefinitionEngine {
    private DefinitionEngine() {}

    public static Optional<DefinitionLocation> getDefinition(String content, int line, int column, String filePath) {
        if (line < 1 || column < 1) return Optional.empty();
        List<Token> tokens = significant(new Lexer(content, filePath).lex().tokens());
        Token target = tokenAt(tokens, line, column);
        if (target == null || target.type() != TokenType.IDENTIFIER) return Optional.empty();
        int targetIndex = tokens.indexOf(target);
        int[] depths = braceDepths(tokens);
        int targetDepth = depths[targetIndex];
        Map<Integer, Token> inferredAtDepth = new HashMap<>();
        Token best = null;
        int bestDepth = -1;

        for (int i = 0; i <= targetIndex; i++) {
            Token token = tokens.get(i);
            if (!token.is(TokenType.IDENTIFIER) || !token.text().equals(target.text())) continue;
            int depth = depths[i];
            if (depth > targetDepth) continue;
            boolean declaration = isFunctionDeclaration(tokens, i) || isExplicitValueDeclaration(tokens, i);
            if (!declaration && isInferredAssignment(tokens, i)) {
                Token first = inferredAtDepth.putIfAbsent(depth, token);
                declaration = first == null || first == token;
            }
            if (!declaration) continue;
            if (depth > bestDepth || depth == bestDepth && (best == null || token.offset() > best.offset())) {
                best = token;
                bestDepth = depth;
            }
        }
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
            if (zeroColumn >= token.column() && zeroColumn < token.column() + token.length()) return token;
        }
        return null;
    }

    private static int[] braceDepths(List<Token> tokens) {
        int[] depths = new int[tokens.size()];
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.is(TokenType.RBRACE)) depth = Math.max(0, depth - 1);
            depths[i] = depth;
            if (token.is(TokenType.LBRACE)) depth++;
        }
        return depths;
    }

    private static boolean isFunctionDeclaration(List<Token> tokens, int index) {
        if (index < 0 || index + 1 >= tokens.size() || !tokens.get(index + 1).is(TokenType.LPAREN)) return false;
        if (index > 0 && tokens.get(index - 1).is(TokenType.DOT)) return false;
        int depth = 0;
        for (int i = index + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.is(TokenType.LPAREN)) depth++;
            else if (token.is(TokenType.RPAREN) && --depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).is(TokenType.LBRACE);
        }
        return false;
    }

    private static boolean isExplicitValueDeclaration(List<Token> tokens, int index) {
        if (index <= 0 || index + 1 >= tokens.size()) return false;
        Token next = tokens.get(index + 1);
        if (!next.is(TokenType.EQ)) return false;
        Token previous = tokens.get(index - 1);
        return previous.is(TokenType.IDENTIFIER) || previous.is(TokenType.GT) || previous.is(TokenType.QUESTION);
    }

    private static boolean isInferredAssignment(List<Token> tokens, int index) {
        if (index + 1 >= tokens.size() || !tokens.get(index + 1).is(TokenType.EQ)) return false;
        return index == 0 || !tokens.get(index - 1).is(TokenType.DOT);
    }
}
