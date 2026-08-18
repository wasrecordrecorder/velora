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
        ScopeLayout scopes = scopes(tokens);
        int targetScope = scopes.scopeAt[targetIndex];
        int scriptScope = scriptScope(tokens, scopes);
        Map<Integer, Declaration> declarations = declarations(tokens, scopes, scriptScope);
        Token best = null;
        int bestScopeDepth = -1;
        int bestOffset = -1;

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (!token.is(TokenType.IDENTIFIER) || !token.text().equals(target.text())) continue;
            Declaration declaration = declarations.get(i);
            if (declaration == null) continue;
            if (i == targetIndex) return Optional.of(location(filePath, token));
            if (i > targetIndex && !declaration.forwardVisible) continue;
            if (!scopes.isAncestor(declaration.visibilityScope, targetScope)) continue;
            int depth = scopes.depth(declaration.visibilityScope);
            int offset = token.offset();
            if (depth > bestScopeDepth || depth == bestScopeDepth && offset > bestOffset && i <= targetIndex) {
                best = token;
                bestScopeDepth = depth;
                bestOffset = offset;
            }
        }
        return best == null ? Optional.empty() : Optional.of(location(filePath, best));
    }

    private static Map<Integer, Declaration> declarations(List<Token> tokens, ScopeLayout scopes, int scriptScope) {
        Map<Integer, Declaration> result = new HashMap<>();
        Map<String, List<Declaration>> byName = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (!token.is(TokenType.IDENTIFIER)) continue;
            Declaration declaration = explicitDeclaration(tokens, scopes, i, scriptScope);
            if (declaration == null && isInferredAssignment(tokens, i)) {
                int scope = scopes.scopeAt[i];
                boolean visible = byName.getOrDefault(token.text(), List.of()).stream()
                        .anyMatch(existing -> scopes.isAncestor(existing.visibilityScope, scope));
                if (!visible) declaration = new Declaration(scope, scope == scriptScope);
            }
            if (declaration == null) continue;
            result.put(i, declaration);
            byName.computeIfAbsent(token.text(), ignored -> new ArrayList<>()).add(declaration);
        }
        return result;
    }

    private static Declaration explicitDeclaration(List<Token> tokens, ScopeLayout scopes, int index, int scriptScope) {
        if (isFunctionDeclaration(tokens, index)) {
            int scope = scopes.scopeAt[index];
            return new Declaration(scope, scope == scriptScope);
        }
        int parameterScope = parameterVisibilityScope(tokens, scopes, index);
        if (parameterScope >= 0) return new Declaration(parameterScope, false);
        int forScope = forVariableVisibilityScope(tokens, scopes, index);
        if (forScope >= 0) return new Declaration(forScope, false);
        if (isExplicitValueDeclaration(tokens, index)) {
            int scope = scopes.scopeAt[index];
            return new Declaration(scope, scope == scriptScope);
        }
        return null;
    }

    private static int parameterVisibilityScope(List<Token> tokens, ScopeLayout scopes, int index) {
        if (index <= 0 || index + 1 >= tokens.size()) return -1;
        Token next = tokens.get(index + 1);
        if (!next.is(TokenType.COMMA) && !next.is(TokenType.RPAREN) && !next.is(TokenType.EQ)) return -1;
        Token previous = tokens.get(index - 1);
        if (!previous.is(TokenType.IDENTIFIER) && !previous.is(TokenType.GT) && !previous.is(TokenType.QUESTION)) return -1;
        int open = enclosingOpenParen(tokens, index);
        if (open <= 0 || !tokens.get(open - 1).is(TokenType.IDENTIFIER)) return -1;
        int close = matchingParen(tokens, open);
        if (close <= index) return -1;
        int body = nextToken(tokens, close + 1, TokenType.LBRACE);
        return body >= 0 ? scopes.openedScope[body] : -1;
    }

    private static int forVariableVisibilityScope(List<Token> tokens, ScopeLayout scopes, int index) {
        if (index + 1 >= tokens.size() || !tokens.get(index + 1).is(TokenType.KW_IN)) return -1;
        int open = enclosingOpenParen(tokens, index);
        if (open <= 0 || !tokens.get(open - 1).is(TokenType.KW_FOR)) return -1;
        int close = matchingParen(tokens, open);
        if (close <= index || close + 1 >= tokens.size() || !tokens.get(close + 1).is(TokenType.LBRACE)) return -1;
        return scopes.openedScope[close + 1];
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

    private static boolean isFunctionDeclaration(List<Token> tokens, int index) {
        if (index < 0 || index + 1 >= tokens.size() || !tokens.get(index + 1).is(TokenType.LPAREN)) return false;
        if (index > 0 && tokens.get(index - 1).is(TokenType.DOT)) return false;
        int close = matchingParen(tokens, index + 1);
        return close >= 0 && close + 1 < tokens.size() && tokens.get(close + 1).is(TokenType.LBRACE);
    }

    private static boolean isExplicitValueDeclaration(List<Token> tokens, int index) {
        if (index <= 0 || index + 1 >= tokens.size() || !tokens.get(index + 1).is(TokenType.EQ)) return false;
        Token previous = tokens.get(index - 1);
        return previous.is(TokenType.IDENTIFIER) || previous.is(TokenType.GT) || previous.is(TokenType.QUESTION);
    }

    private static boolean isInferredAssignment(List<Token> tokens, int index) {
        if (index + 1 >= tokens.size() || !tokens.get(index + 1).is(TokenType.EQ)) return false;
        return index == 0 || !tokens.get(index - 1).is(TokenType.DOT);
    }

    private static int enclosingOpenParen(List<Token> tokens, int index) {
        int depth = 0;
        for (int i = index - 1; i >= 0; i--) {
            if (tokens.get(i).is(TokenType.RPAREN)) depth++;
            else if (tokens.get(i).is(TokenType.LPAREN)) {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    private static int matchingParen(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).is(TokenType.LPAREN)) depth++;
            else if (tokens.get(i).is(TokenType.RPAREN) && --depth == 0) return i;
        }
        return -1;
    }

    private static int nextToken(List<Token> tokens, int from, TokenType type) {
        return from < tokens.size() && tokens.get(from).is(type) ? from : -1;
    }

    private static int scriptScope(List<Token> tokens, ScopeLayout scopes) {
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).is(TokenType.KW_SCRIPT) && tokens.get(i + 1).is(TokenType.IDENTIFIER)) {
                for (int j = i + 2; j < tokens.size(); j++) {
                    if (tokens.get(j).is(TokenType.LBRACE)) return scopes.openedScope[j];
                    if (tokens.get(j).is(TokenType.KW_SCRIPT)) break;
                }
            }
        }
        return 0;
    }

    private static ScopeLayout scopes(List<Token> tokens) {
        int[] scopeAt = new int[tokens.size()];
        int[] openedScope = new int[tokens.size()];
        Map<Integer, Integer> parents = new HashMap<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(0);
        int next = 1;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.is(TokenType.RBRACE) && stack.size() > 1) stack.pop();
            scopeAt[i] = stack.peek();
            if (token.is(TokenType.LBRACE)) {
                int child = next++;
                openedScope[i] = child;
                parents.put(child, stack.peek());
                stack.push(child);
            }
        }
        return new ScopeLayout(scopeAt, openedScope, parents);
    }

    private static DefinitionLocation location(String filePath, Token token) {
        return DefinitionLocation.of(filePath, token.line(), token.column() + 1);
    }

    private record Declaration(int visibilityScope, boolean forwardVisible) {}

    private record ScopeLayout(int[] scopeAt, int[] openedScope, Map<Integer, Integer> parents) {
        private boolean isAncestor(int ancestor, int scope) {
            int current = scope;
            while (true) {
                if (current == ancestor) return true;
                Integer parent = parents.get(current);
                if (parent == null) return false;
                current = parent;
            }
        }

        private int depth(int scope) {
            int depth = 0;
            Integer current = scope;
            while (current != null && current != 0) {
                depth++;
                current = parents.get(current);
            }
            return depth;
        }
    }
}
