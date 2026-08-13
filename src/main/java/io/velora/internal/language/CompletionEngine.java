package io.velora.internal.language;

import io.velora.api.language.CompletionItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CompletionEngine {
    private static final List<String> KEYWORDS = List.of(
            "script", "settings", "static", "async", "entry", "event", "void", "if", "else",
            "while", "for", "when", "return", "import", "package", "private", "public", "enum",
            "data", "is", "in", "spawn", "true", "false", "null"
    );
    private static final List<String> TYPES = List.of(
            "boolean", "byte", "int", "long", "float", "double", "char", "String", "Duration",
            "Vec2", "Vec3", "Color", "UUID", "List", "Map", "Set", "Task"
    );
    private static final List<String> BUILTINS = List.of("await", "delay", "yield");

    private CompletionEngine() {}

    public static List<CompletionItem> getCompletions(String content, int line, int column) {
        String prefix = prefixAt(content, line, column);
        Set<String> seen = new LinkedHashSet<>();
        List<CompletionItem> result = new ArrayList<>();
        add(result, seen, KEYWORDS, CompletionItem.CompletionKind.KEYWORD, prefix);
        add(result, seen, TYPES, CompletionItem.CompletionKind.TYPE, prefix);
        add(result, seen, BUILTINS, CompletionItem.CompletionKind.FUNCTION, prefix);
        return List.copyOf(result);
    }

    private static void add(List<CompletionItem> result, Set<String> seen, List<String> values, CompletionItem.CompletionKind kind, String prefix) {
        for (String value : values) {
            if ((prefix.isEmpty() || value.regionMatches(true, 0, prefix, 0, prefix.length())) && seen.add(value)) {
                result.add(CompletionItem.of(value, kind));
            }
        }
    }

    private static String prefixAt(String content, int line, int column) {
        if (line < 1 || column < 1) return "";
        String[] lines = content.split("\\R", -1);
        if (line > lines.length) return "";
        String source = lines[line - 1];
        int cursor = Math.min(column - 1, source.length());
        int start = cursor;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) start--;
        return source.substring(start, cursor);
    }
}
