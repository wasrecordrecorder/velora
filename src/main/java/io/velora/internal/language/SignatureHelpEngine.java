package io.velora.internal.language;

import io.velora.api.function.ApiRegistry;
import io.velora.api.language.SignatureHelp;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SignatureHelpEngine {
    private SignatureHelpEngine() {}

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column) {
        return getSignatureHelp(content, line, column, null, null);
    }

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column, ApiRegistry apiRegistry) {
        return getSignatureHelp(content, line, column, apiRegistry, null);
    }

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column, ApiRegistry apiRegistry,
                                                            JavaImportRegistry javaImportRegistry) {
        if (line < 1 || column < 1) return Optional.empty();
        int cursor = cursorOffset(content, line, column);
        if (cursor < 0) return Optional.empty();
        List<Token> tokens = new ArrayList<>();
        for (Token token : new Lexer(content, "signature.vls").lex().tokens()) {
            if (!token.isTrivia() && !token.is(TokenType.EOF) && token.offset() < cursor) tokens.add(token);
        }
        ArrayDeque<Delimiter> stack = new ArrayDeque<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.is(TokenType.LPAREN) || token.is(TokenType.LBRACKET) || token.is(TokenType.LBRACE)) stack.push(new Delimiter(token.type(), i));
            else if (token.is(TokenType.COMMA) && !stack.isEmpty() && stack.peek().type == TokenType.LPAREN) stack.peek().commas++;
            else if (token.is(TokenType.RPAREN)) close(stack, TokenType.LPAREN);
            else if (token.is(TokenType.RBRACKET)) close(stack, TokenType.LBRACKET);
            else if (token.is(TokenType.RBRACE)) close(stack, TokenType.LBRACE);
        }
        for (Delimiter delimiter : stack) {
            if (delimiter.type != TokenType.LPAREN) continue;
            String name = callee(tokens, delimiter.tokenIndex);
            if (name != null) return Optional.of(buildSignature(content, name, delimiter.commas, apiRegistry, javaImportRegistry));
        }
        return Optional.empty();
    }

    private static void close(ArrayDeque<Delimiter> stack, TokenType type) {
        if (!stack.isEmpty() && stack.peek().type == type) stack.pop();
    }

    private static String callee(List<Token> tokens, int parenIndex) {
        int i = parenIndex - 1;
        if (i < 0 || !tokens.get(i).is(TokenType.IDENTIFIER)) return null;
        StringBuilder name = new StringBuilder(tokens.get(i).text());
        if (i > 0 && tokens.get(i - 1).is(TokenType.AT)) return "@" + name;
        while (i >= 2 && tokens.get(i - 1).is(TokenType.DOT) && tokens.get(i - 2).is(TokenType.IDENTIFIER)) {
            name.insert(0, tokens.get(i - 2).text() + ".");
            i -= 2;
        }
        return name.toString();
    }

    private static int cursorOffset(String content, int line, int column) {
        int currentLine = 1;
        int lineStart = 0;
        for (int i = 0; i < content.length() && currentLine < line; i++) {
            char c = content.charAt(i);
            if (c == '\n') { currentLine++; lineStart = i + 1; }
        }
        if (currentLine != line) return -1;
        int lineEnd = content.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = content.length();
        if (lineEnd > lineStart && content.charAt(lineEnd - 1) == '\r') lineEnd--;
        return Math.min(lineStart + column - 1, lineEnd);
    }

    private static final class Delimiter {
        private final TokenType type;
        private final int tokenIndex;
        private int commas;

        private Delimiter(TokenType type, int tokenIndex) {
            this.type = type;
            this.tokenIndex = tokenIndex;
        }
    }

    private static SignatureHelp buildSignature(String content, String name, int activeParameter, ApiRegistry apiRegistry, JavaImportRegistry javaImportRegistry) {
        if (apiRegistry != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot + 1 < name.length()) {
                var descriptor = apiRegistry.find(JavaImportAliases.namespace(content, name.substring(0, dot), javaImportRegistry), name.substring(dot + 1));
                if (descriptor != null) {
                    List<SignatureHelp.SignatureParameter> parameters = descriptor.parameters().stream()
                            .map(parameter -> new SignatureHelp.SignatureParameter(parameter.name(), parameter.type().name(), parameter.hasDefault() ? "Default: " + parameter.defaultValue() : null))
                            .toList();
                    return new SignatureHelp(descriptor.qualifiedName(), parameters, Math.min(activeParameter, Math.max(0, parameters.size() - 1)), descriptor.description());
                }
            }
        }
        if (name.equals("@Script") || name.equals("Script")) {
            return new SignatureHelp("@Script", List.of(
                    new SignatureHelp.SignatureParameter("name", "String", "Script display name"),
                    new SignatureHelp.SignatureParameter("version", "String", "Script version")
            ), Math.min(activeParameter, 1), null);
        }
        if (name.equals("delay")) return new SignatureHelp("delay", List.of(new SignatureHelp.SignatureParameter("duration", "Duration", "Suspend duration")), 0, null);
        if (name.equals("await")) return new SignatureHelp("await", List.of(new SignatureHelp.SignatureParameter("task", "Task<T>", "Task to await")), 0, null);
        return new SignatureHelp(name, List.of(), 0, null);
    }
}
