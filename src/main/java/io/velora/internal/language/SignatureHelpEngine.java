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
            else if (token.is(TokenType.COMMA) && !stack.isEmpty() && stack.peek().type == TokenType.LPAREN) {
                stack.peek().commas++;
                stack.peek().lastCommaTokenIndex = i;
            } else if (token.is(TokenType.RPAREN)) close(stack, TokenType.LPAREN);
            else if (token.is(TokenType.RBRACKET)) close(stack, TokenType.LBRACKET);
            else if (token.is(TokenType.RBRACE)) close(stack, TokenType.LBRACE);
        }
        for (Delimiter delimiter : stack) {
            if (delimiter.type != TokenType.LPAREN) continue;
            String name = callee(tokens, delimiter.tokenIndex);
            String namedArg = currentNamedArg(tokens, Math.max(delimiter.tokenIndex, delimiter.lastCommaTokenIndex) + 1);
            if (name != null) return Optional.of(buildSignature(content, name, delimiter.commas, namedArg, cursor, apiRegistry, javaImportRegistry, typeRegistry, resolved));
        }
        return Optional.empty();
    }

    private static void close(ArrayDeque<Delimiter> stack, TokenType type) {
        if (!stack.isEmpty() && stack.peek().type == type) stack.pop();
    }

    private static String currentNamedArg(List<Token> tokens, int argStartIndex) {
        if (argStartIndex + 1 < tokens.size() && tokens.get(argStartIndex).is(TokenType.IDENTIFIER) && tokens.get(argStartIndex + 1).is(TokenType.EQ)) {
            return tokens.get(argStartIndex).text();
        }
        return null;
    }

    private static String callee(List<Token> tokens, int parenIndex) {
        int i = parenIndex - 1;
        if (i < 0 || !tokens.get(i).is(TokenType.IDENTIFIER)) return null;
        String methodName = tokens.get(i).text();
        if (i > 0 && tokens.get(i - 1).is(TokenType.AT)) return "@" + methodName;
        if (i >= 2 && tokens.get(i - 1).is(TokenType.DOT)) {
            int dot = i - 1;
            if (dot > 0 && tokens.get(dot - 1).is(TokenType.QUESTION)) dot--;
            int start = dot;
            int p = 0, b = 0, a = 0;
            while (start > 0) {
                Token prev = tokens.get(start - 1);
                if (prev.is(TokenType.RPAREN)) { p++; start--; continue; }
                if (prev.is(TokenType.LPAREN)) { if (p == 0) break; p--; start--; continue; }
                if (prev.is(TokenType.RBRACKET)) { b++; start--; continue; }
                if (prev.is(TokenType.LBRACKET)) { if (b == 0) break; b--; start--; continue; }
                if (prev.is(TokenType.GT) && p == 0 && b == 0) { a++; start--; continue; }
                if (prev.is(TokenType.LT) && a > 0) { a--; start--; continue; }
                if (p > 0 || b > 0 || a > 0) { start--; continue; }
                if (prev.is(TokenType.IDENTIFIER) || prev.is(TokenType.DOT) || prev.is(TokenType.QUESTION)
                        || prev.is(TokenType.STRING) || prev.is(TokenType.STRING_INTERP)
                        || prev.is(TokenType.INTEGER) || prev.is(TokenType.LONG_LITERAL)
                        || prev.is(TokenType.FLOAT_LITERAL) || prev.is(TokenType.DOUBLE_LITERAL)) {
                    start--;
                } else break;
            }
            if (start == dot || p != 0 || b != 0) return methodName;
            StringBuilder receiver = new StringBuilder();
            for (int k = start; k < dot; k++) receiver.append(tokens.get(k).text());
            return receiver + "." + methodName;
        }
        return methodName;
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
        private int lastCommaTokenIndex = -1;

        private Delimiter(TokenType type, int tokenIndex) {
            this.type = type;
            this.tokenIndex = tokenIndex;
        }
    }

    private static SignatureHelp buildSignature(String content, String name, int activeParameter, String namedArg, int cursor, ApiRegistry apiRegistry,
                                                JavaImportRegistry javaImportRegistry, TypeRegistry typeRegistry, ResolvedScript resolved) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot + 1 < name.length()) {
            String qualifier = name.substring(0, dot);
            String member = name.substring(dot + 1);
            if (apiRegistry != null && qualifier.indexOf('.') < 0) {
                var descriptor = apiRegistry.find(JavaImportAliases.namespace(content, qualifier, javaImportRegistry), member);
                if (descriptor != null && !descriptor.property()) {
                    SignatureHelp help = LanguageMetadata.signatureHelp(descriptor, activeParameter);
                    return applyNamedParam(help, namedArg);
                }
            }
            var receiverType = LanguageMetadata.qualifierType(content, qualifier, cursor, apiRegistry, typeRegistry, null, javaImportRegistry, resolved);
            var builtin = LanguageMetadata.member(receiverType, member);
            if (builtin != null && !builtin.property()) return applyNamedParam(builtin.signature(activeParameter), namedArg);
        }
        if (resolved != null) {
            var fn = resolved.functions().get(name);
            if (fn != null) {
                List<SignatureHelp.SignatureParameter> params = fn.parameters().stream().map(param -> new SignatureHelp.SignatureParameter(
                        param.name(),
                        param.type().name(),
                        param.hasDefault() ? "Default: " + param.defaultValue() : null
                )).toList();
                int active = params.isEmpty() ? 0 : Math.min(activeParameter, params.size() - 1);
                return applyNamedParam(new SignatureHelp(fn.name(), params, active, "Returns " + fn.returnType().name()), namedArg);
            }
        }
        if (name.equals("@Script") || name.equals("Script")) return signature("@Script", activeParameter, "Declares the script display name.",
                parameter("name", "String", "Script display name."));
        if (name.equals("@Version") || name.equals("Version")) return signature("@Version", activeParameter, "Sets optional script version metadata.", parameter("value", "String", "Version string."));
        if (name.equals("@Author") || name.equals("Author")) return signature("@Author", activeParameter, "Sets optional script author metadata.", parameter("value", "String", "Author name."));
        if (name.equals("@Description") || name.equals("Description")) return signature("@Description", activeParameter, "Sets optional script description metadata.", parameter("value", "String", "Human-readable description."));
        if (name.equals("@Persistent") || name.equals("Persistent")) return signature("@Persistent", activeParameter, "Persists a mutable field across reloads.", parameter("id", "String?", "Optional stable persistence id."));
        if (name.equals("@Setting") || name.equals("Setting")) return applyNamedParam(signature("@Setting", activeParameter, "Exposes a field as a client setting.",
                parameter("name", "String?", "Optional display name."), parameter("min", "Number?", "Optional minimum numeric value."),
                parameter("max", "Number?", "Optional maximum numeric value."), parameter("step", "Number?", "Optional positive numeric step."),
                parameter("description", "String?", "Optional description."), parameter("values", "List?", "Allowed option values."),
                parameter("minLength", "Int?", "Minimum string length."), parameter("maxLength", "Int?", "Maximum string length."),
                parameter("pattern", "String?", "Regex validation pattern."), parameter("editor", "String?", "Editor id."),
                parameter("kind", "String?", "Setting kind.")), namedArg);
        if (name.equals("delay")) return signature("delay", 0, "Suspends the current fiber for a duration.", parameter("duration", "Duration", "Suspend duration."));
        if (name.equals("await")) return signature("await", 0, "Suspends until a task completes and returns its result.", parameter("task", "Task<T>", "Task to await."));
        return new SignatureHelp(name, List.of(), 0, null);
    }

    private static SignatureHelp applyNamedParam(SignatureHelp help, String namedArg) {
        if (namedArg == null || help.parameters().isEmpty()) return help;
        for (int i = 0; i < help.parameters().size(); i++) {
            String paramName = help.parameters().get(i).name();
            if (paramName.equals(namedArg) || paramName.equals(namedArg + "...")) {
                return new SignatureHelp(help.functionName(), help.parameters(), i, help.documentation());
            }
        }
        return help;
    }

    private static SignatureHelp signature(String name, int activeParameter, String documentation, SignatureHelp.SignatureParameter... parameters) {
        List<SignatureHelp.SignatureParameter> list = List.of(parameters);
        return new SignatureHelp(name, list, list.isEmpty() ? 0 : Math.min(activeParameter, list.size() - 1), documentation);
    }

    private static SignatureHelp.SignatureParameter parameter(String name, String type, String documentation) {
        return new SignatureHelp.SignatureParameter(name, type, documentation);
    }
}
