package io.velora.internal.language;

import io.velora.api.function.ApiRegistry;
import io.velora.api.language.HoverInfo;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.Map;
import java.util.Optional;

public final class HoverEngine {
    private static final Map<String, String> INFO = Map.ofEntries(
            Map.entry("script", "Declares a Velora script."),
            Map.entry("async", "Marks a function, entry or event handler as suspendable."),
            Map.entry("spawn", "Starts a script function in a child fiber and returns Task<T>."),
            Map.entry("await", "Suspends until a Task<T> completes and returns its result."),
            Map.entry("delay", "Suspends the current fiber for a Duration or nanosecond value."),
            Map.entry("yield", "Yields the current fiber until the next scheduler opportunity."),
            Map.entry("settings", "Declares script settings."),
            Map.entry("entry", "Declares a lifecycle entry point."),
            Map.entry("event", "Declares an event handler."),
            Map.entry("return", "Returns a value from the current function."),
            Map.entry("Duration", "A duration value. Literals include 500.ms, 2.seconds and 1.minute."),
            Map.entry("Task", "A handle to a spawned script computation: Task<T>.")
    );

    private HoverEngine() {}

    public static Optional<HoverInfo> getHover(String content, int line, int column, String filePath) {
        return getHover(content, line, column, filePath, null, null, null);
    }

    public static Optional<HoverInfo> getHover(String content, int line, int column, String filePath, ApiRegistry apiRegistry,
                                               TypeRegistry typeRegistry, ConstantRegistry constantRegistry) {
        if (line < 1 || column < 1) return Optional.empty();
        int zeroColumn = column - 1;
        for (Token token : new Lexer(content, filePath).lex().tokens()) {
            if (token.line() != line || token.length() == 0) continue;
            if (zeroColumn < token.column() || zeroColumn >= token.column() + token.length()) continue;
            if (token.isTrivia() || token.is(TokenType.EOF)) return Optional.empty();
            String qualified = qualifiedAt(content, line, token.column(), token.text());
            String detail = dynamicInfo(token.text(), qualified, apiRegistry, typeRegistry, constantRegistry);
            if (detail == null) detail = INFO.get(token.text());
            String body = "```velora\n" + (qualified != null ? qualified : token.text()) + "\n```" + (detail == null ? "" : "\n" + detail);
            return Optional.of(HoverInfo.of(body, filePath, token.line(), token.column() + 1));
        }
        return Optional.empty();
    }

    private static String dynamicInfo(String token, String qualified, ApiRegistry apiRegistry, TypeRegistry typeRegistry, ConstantRegistry constantRegistry) {
        if (qualified != null) {
            int dot = qualified.indexOf('.');
            String namespace = qualified.substring(0, dot);
            String member = qualified.substring(dot + 1);
            if (apiRegistry != null) {
                var function = apiRegistry.find(namespace, member);
                if (function != null) return function.description() + "\nReturns `" + function.returnType().name() + "`.";
            }
            if (constantRegistry != null) {
                var constant = constantRegistry.find(namespace, member);
                if (constant != null) return "Constant `" + constant.type().name() + "`.";
            }
        }
        if (typeRegistry != null && typeRegistry.find(token) != null) return "Registered Velora type `" + typeRegistry.find(token).name() + "`.";
        return null;
    }

    private static String qualifiedAt(String content, int line, int tokenColumn, String token) {
        String[] lines = content.split("\\R", -1);
        if (line < 1 || line > lines.length || tokenColumn <= 0) return null;
        String source = lines[line - 1];
        int dot = tokenColumn - 1;
        while (dot >= 0 && Character.isWhitespace(source.charAt(dot))) dot--;
        if (dot < 0 || source.charAt(dot) != '.') return null;
        int end = dot;
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) start--;
        return start == end ? null : source.substring(start, end) + "." + token;
    }
}
