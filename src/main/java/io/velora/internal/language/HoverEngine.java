package io.velora.internal.language;

import io.velora.api.language.HoverInfo;
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
        if (line < 1 || column < 1) return Optional.empty();
        int zeroColumn = column - 1;
        for (Token token : new Lexer(content, filePath).lex().tokens()) {
            if (token.line() != line || token.length() == 0) continue;
            if (zeroColumn < token.column() || zeroColumn > token.column() + token.length()) continue;
            if (token.isTrivia() || token.is(TokenType.EOF)) return Optional.empty();
            String detail = INFO.get(token.text());
            String body = "```velora\n" + token.text() + "\n```" + (detail == null ? "" : "\n" + detail);
            return Optional.of(HoverInfo.of(body, filePath, token.line(), token.column() + 1));
        }
        return Optional.empty();
    }
}
