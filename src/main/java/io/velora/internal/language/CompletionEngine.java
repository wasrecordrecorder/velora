package io.velora.internal.language;

import io.velora.api.language.CompletionItem;

import java.util.*;

public final class CompletionEngine {

    private static final Set<String> KEYWORDS = Set.of(
            "script", "val", "var", "fun", "suspend", "if", "else", "while", "for",
            "in", "when", "is", "return", "true", "false", "null", "onLoad", "onEnable",
            "onDisable", "onUnload", "onRun", "onTick", "onEvent", "setting", "import",
            "private", "internal", "enum", "data", "spawn", "await", "delay", "yield"
    );

    public static List<CompletionItem> getCompletions(String content, int line, int column) {
        List<CompletionItem> items = new ArrayList<>();
        for (String kw : KEYWORDS) {
            items.add(CompletionItem.of(kw, CompletionItem.CompletionKind.KEYWORD));
        }
        return items;
    }
}
