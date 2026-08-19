package io.velora.internal.language;

import io.velora.api.function.ApiRegistry;
import io.velora.api.language.SignatureHelp;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.internal.semantic.ResolvedScript;
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
        return getSignatureHelp(content, line, column, null, null, null, null);
    }

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column, ApiRegistry apiRegistry) {
        return getSignatureHelp(content, line, column, apiRegistry, null, null, null);
    }

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column, ApiRegistry apiRegistry,
                                                            JavaImportRegistry javaImportRegistry) {
        return getSignatureHelp(content, line, column, apiRegistry, javaImportRegistry, null, null);
    }

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column, ApiRegistry apiRegistry,
                                                            JavaImportRegistry javaImportRegistry, TypeRegistry typeRegistry,
                                                            ResolvedScript resolved) {
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
            if (name != null) return Optional.of(buildSignature(content, name, delimiter.commas, cursor, apiRegistry, javaImportRegistry, typeRegistry, resolved));
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
        while (i >= 2 && tokens.get(i - 1).is(TokenType.DOT)) {
            if (tokens.get(i - 2).is(TokenType.IDENTIFIER)) {
                name.insert(0, tokens.get(i - 2).text() + ".");
                i -= 2;
            } else if (i >= 3 && tokens.get(i - 2).is(TokenType.QUESTION) && tokens.get(i - 3).is(TokenType.IDENTIFIER)) {
                name.insert(0, tokens.get(i - 3).text() + ".");
                i -= 3;
            } else break;
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

    private static SignatureHelp buildSignature(String content, String name, int activeParameter, int cursor, ApiRegistry apiRegistry,
                                                JavaImportRegistry javaImportRegistry, TypeRegistry typeRegistry, ResolvedScript resolved) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot + 1 < name.length()) {
            String qualifier = name.substring(0, dot);
            String member = name.substring(dot + 1);
            if (apiRegistry != null) {
                var descriptor = apiRegistry.find(JavaImportAliases.namespace(content, qualifier, javaImportRegistry), member);
                if (descriptor != null && !descriptor.property()) return LanguageMetadata.signatureHelp(descriptor, activeParameter);
            }
            var receiverType = LanguageMetadata.qualifierType(content, qualifier, cursor, typeRegistry, resolved);
            var builtin = LanguageMetadata.member(receiverType, member);
            if (builtin != null && !builtin.property()) return builtin.signature(activeParameter);
        }
        if (name.equals("@Script") || name.equals("Script")) return signature("@Script", activeParameter, "Declares the script display name.",
                parameter("name", "String", "Script display name."));
        if (name.equals("@Version") || name.equals("Version")) return signature("@Version", activeParameter, "Sets optional script version metadata.", parameter("value", "String", "Version string."));
        if (name.equals("@Author") || name.equals("Author")) return signature("@Author", activeParameter, "Sets optional script author metadata.", parameter("value", "String", "Author name."));
        if (name.equals("@Description") || name.equals("Description")) return signature("@Description", activeParameter, "Sets optional script description metadata.", parameter("value", "String", "Human-readable description."));
        if (name.equals("@Persistent") || name.equals("Persistent")) return signature("@Persistent", activeParameter, "Persists a mutable field across reloads.", parameter("id", "String?", "Optional stable persistence id."));
        if (name.equals("@Setting") || name.equals("Setting")) return signature("@Setting", activeParameter, "Exposes a field as a client setting.",
                parameter("name", "String?", "Optional display name."), parameter("min", "Number?", "Optional minimum numeric value."),
                parameter("max", "Number?", "Optional maximum numeric value."), parameter("step", "Number?", "Optional positive numeric step."));
        if (name.equals("delay")) return signature("delay", 0, "Suspends the current fiber for a duration.", parameter("duration", "Duration", "Suspend duration."));
        if (name.equals("await")) return signature("await", 0, "Suspends until a task completes and returns its result.", parameter("task", "Task<T>", "Task to await."));
        return new SignatureHelp(name, List.of(), 0, null);
    }

    private static SignatureHelp signature(String name, int activeParameter, String documentation, SignatureHelp.SignatureParameter... parameters) {
        List<SignatureHelp.SignatureParameter> list = List.of(parameters);
        return new SignatureHelp(name, list, list.isEmpty() ? 0 : Math.min(activeParameter, list.size() - 1), documentation);
    }

    private static SignatureHelp.SignatureParameter parameter(String name, String type, String documentation) {
        return new SignatureHelp.SignatureParameter(name, type, documentation);
    }
}
