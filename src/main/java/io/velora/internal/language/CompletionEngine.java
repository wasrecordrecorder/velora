package io.velora.internal.language;

import io.velora.api.event.EventRegistry;
import io.velora.api.function.ApiRegistry;
import io.velora.api.language.CompletionItem;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.SettingRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.type.EnumType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CompletionEngine {
    private static final List<String> KEYWORDS = List.of(
            "script", "settings", "static", "async", "entry", "event", "void", "if", "else",
            "while", "for", "when", "return", "is", "in",
            "spawn", "true", "false", "null"
    );
    private static final List<String> TYPES = List.of(
            "boolean", "byte", "int", "long", "float", "double", "char", "String", "Duration",
            "Vec2", "Vec3", "Color", "UUID", "List", "Map", "Set", "Task"
    );
    private static final List<String> BUILTINS = List.of("await", "delay", "yield");

    private CompletionEngine() {}

    public static List<CompletionItem> getCompletions(String content, int line, int column) {
        return getCompletions(content, line, column, null, null, null, null, null);
    }

    public static List<CompletionItem> getCompletions(String content, int line, int column, ApiRegistry apiRegistry,
                                                       TypeRegistry typeRegistry, EventRegistry eventRegistry,
                                                       SettingRegistry settingRegistry, ConstantRegistry constantRegistry) {
        String prefix = prefixAt(content, line, column);
        String qualifier = qualifierAt(content, line, column, prefix.length());
        Set<String> seen = new LinkedHashSet<>();
        List<CompletionItem> result = new ArrayList<>();
        if (qualifier != null) {
            if (apiRegistry != null) {
                for (var function : apiRegistry.all()) {
                    if (function.namespace().equals(qualifier) && matches(function.name(), prefix) && seen.add(function.name())) {
                        String detail = function.qualifiedName() + "(" + String.join(", ", function.parameters().stream().map(p -> p.name() + ": " + p.type().name()).toList()) + ") -> " + function.returnType().name();
                        result.add(new CompletionItem(function.name(), detail, function.description(), function.name(), CompletionItem.CompletionKind.FUNCTION));
                    }
                }
            }
            if (constantRegistry != null) {
                for (var constant : constantRegistry.namespace(qualifier)) {
                    if (matches(constant.member(), prefix) && seen.add(constant.member())) result.add(new CompletionItem(constant.member(), constant.type().name(), null, constant.member(), CompletionItem.CompletionKind.CONSTANT));
                }
            }
            if (typeRegistry != null && typeRegistry.find(qualifier) instanceof EnumType enumType) {
                for (var constant : enumType.constants()) {
                    if (matches(constant.name(), prefix) && seen.add(constant.name())) result.add(new CompletionItem(constant.name(), enumType.name(), null, constant.name(), CompletionItem.CompletionKind.ENUM_CONSTANT));
                }
            }
            return List.copyOf(result);
        }
        add(result, seen, KEYWORDS, CompletionItem.CompletionKind.KEYWORD, prefix);
        add(result, seen, TYPES, CompletionItem.CompletionKind.TYPE, prefix);
        add(result, seen, BUILTINS, CompletionItem.CompletionKind.FUNCTION, prefix);
        if (typeRegistry != null) add(result, seen, typeRegistry.names().stream().toList(), CompletionItem.CompletionKind.TYPE, prefix);
        if (apiRegistry != null) add(result, seen, apiRegistry.namespaces().stream().toList(), CompletionItem.CompletionKind.NAMESPACE, prefix);
        if (constantRegistry != null) add(result, seen, constantRegistry.namespaces().stream().toList(), CompletionItem.CompletionKind.NAMESPACE, prefix);
        if (settingRegistry != null && insideSettings(content, line, column)) {
            for (String kind : settingRegistry.names()) {
                String label = "@" + kind;
                if (matches(kind, prefix) && seen.add(label)) result.add(new CompletionItem(label, "Setting", null, label, CompletionItem.CompletionKind.SETTING));
            }
        }
        if (eventRegistry != null) {
            for (var event : eventRegistry.all()) {
                String label = "Event." + event.scriptName();
                if (matches(label, prefix) && seen.add(label)) result.add(new CompletionItem(label, event.payloadType().name(), event.description(), label, CompletionItem.CompletionKind.CONSTANT));
            }
        }
        return List.copyOf(result);
    }

    private static void add(List<CompletionItem> result, Set<String> seen, List<String> values, CompletionItem.CompletionKind kind, String prefix) {
        for (String value : values) if (matches(value, prefix) && seen.add(value)) result.add(CompletionItem.of(value, kind));
    }

    private static boolean matches(String value, String prefix) {
        return prefix.isEmpty() || value.regionMatches(true, 0, prefix, 0, prefix.length());
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

    private static String qualifierAt(String content, int line, int column, int prefixLength) {
        String[] lines = content.split("\\R", -1);
        if (line < 1 || line > lines.length) return null;
        String source = lines[line - 1];
        int cursor = Math.min(column - 1, source.length()) - prefixLength;
        if (cursor <= 0 || source.charAt(cursor - 1) != '.') return null;
        int end = cursor - 1;
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) start--;
        return start == end ? null : source.substring(start, end);
    }

    private static boolean insideSettings(String content, int line, int column) {
        String[] lines = content.split("\\R", -1);
        if (line < 1 || line > lines.length) return false;
        StringBuilder before = new StringBuilder();
        for (int i = 0; i < line - 1; i++) before.append(lines[i]).append('\n');
        before.append(lines[line - 1], 0, Math.min(column - 1, lines[line - 1].length()));
        int settings = before.lastIndexOf("settings");
        if (settings < 0) return false;
        int open = before.indexOf("{", settings);
        if (open < 0) return false;
        int depth = 0;
        for (int i = open; i < before.length(); i++) {
            char c = before.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth > 0;
    }
}
