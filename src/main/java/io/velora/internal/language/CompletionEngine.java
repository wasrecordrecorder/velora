package io.velora.internal.language;

import io.velora.api.event.EventRegistry;
import io.velora.api.function.ApiRegistry;
import io.velora.api.language.CompletionItem;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.SettingRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.type.EnumType;
import io.velora.internal.semantic.ResolvedScript;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.TokenType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CompletionEngine {
    private static final List<String> KEYWORDS = List.of(
            "script", "import", "static", "async", "if", "else", "while", "for", "when", "return", "is", "in",
            "spawn", "true", "false", "null"
    );
    private static final List<String> TYPES = List.of(
            "boolean", "byte", "int", "long", "float", "double", "char", "String", "Duration",
            "Vec2", "Vec3", "Color", "UUID", "List", "Map", "Set", "Task"
    );
    private static final List<String> BUILTINS = List.of("list", "set", "map", "await", "delay", "yield");
    private static final List<String> ANNOTATIONS = List.of(
            "Script", "Version", "Author", "Description", "Setting", "Persistent",
            "Load", "Enable", "Run", "Disable", "Unload"
    );

    private CompletionEngine() {}

    public static List<CompletionItem> getCompletions(String content, int line, int column) {
        return getCompletions(content, line, column, null, null, null, null, null, null, null);
    }

    public static List<CompletionItem> getCompletions(String content, int line, int column, ApiRegistry apiRegistry,
                                                       TypeRegistry typeRegistry, EventRegistry eventRegistry,
                                                       SettingRegistry settingRegistry, ConstantRegistry constantRegistry) {
        return getCompletions(content, line, column, apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, null, null);
    }

    public static List<CompletionItem> getCompletions(String content, int line, int column, ApiRegistry apiRegistry,
                                                       TypeRegistry typeRegistry, EventRegistry eventRegistry,
                                                       SettingRegistry settingRegistry, ConstantRegistry constantRegistry,
                                                       JavaImportRegistry javaImportRegistry) {
        return getCompletions(content, line, column, apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, javaImportRegistry, null);
    }

    public static List<CompletionItem> getCompletions(String content, int line, int column, ApiRegistry apiRegistry,
                                                       TypeRegistry typeRegistry, EventRegistry eventRegistry,
                                                       SettingRegistry settingRegistry, ConstantRegistry constantRegistry,
                                                       JavaImportRegistry javaImportRegistry, ResolvedScript resolved) {
        if (blockedContext(content, line, column)) return List.of();
        String prefix = prefixAt(content, line, column);
        String qualifier = qualifierAt(content, line, column, prefix.length());
        Set<String> seen = new LinkedHashSet<>();
        List<CompletionItem> result = new ArrayList<>();
        if (qualifier != null) {
            if (apiRegistry != null) {
                String namespace = JavaImportAliases.namespace(content, qualifier, javaImportRegistry);
                for (var function : apiRegistry.all()) {
                    if (function.namespace().equals(namespace) && matches(function.name(), prefix) && seen.add(function.name())) {
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
        if (annotationContext(content, line, column, prefix.length())) {
            addAnnotations(result, seen, ANNOTATIONS, prefix, null, null);
            if (eventRegistry != null) {
                for (var event : eventRegistry.all()) addAnnotation(result, seen, event.scriptName(), prefix, event.payloadType().name(), event.description());
            }
            return List.copyOf(result);
        }
        add(result, seen, KEYWORDS, CompletionItem.CompletionKind.KEYWORD, prefix);
        add(result, seen, TYPES, CompletionItem.CompletionKind.TYPE, prefix);
        add(result, seen, BUILTINS, CompletionItem.CompletionKind.FUNCTION, prefix);
        if (resolved != null) {
            for (var setting : resolved.settings()) {
                if (matches(setting.id(), prefix) && seen.add(setting.id())) result.add(new CompletionItem(setting.id(), setting.type().name(), setting.description().orElse(null), setting.id(), CompletionItem.CompletionKind.SETTING));
            }
            for (var property : resolved.properties().values()) {
                if (matches(property.name(), prefix) && seen.add(property.name())) result.add(new CompletionItem(property.name(), property.type().name(), property.persistent() ? "Persistent" : null, property.name(), property.isConst() ? CompletionItem.CompletionKind.CONSTANT : CompletionItem.CompletionKind.PROPERTY));
            }
            for (var function : resolved.functions().values()) {
                if (matches(function.name(), prefix) && seen.add(function.name())) {
                    String detail = function.name() + "(" + String.join(", ", function.parameters().stream().map(parameter -> parameter.name() + ": " + parameter.type().name()).toList()) + ") -> " + function.returnType().name();
                    result.add(new CompletionItem(function.name(), detail, null, function.name(), CompletionItem.CompletionKind.FUNCTION));
                }
            }
        }
        if (typeRegistry != null) add(result, seen, typeRegistry.names().stream().toList(), CompletionItem.CompletionKind.TYPE, prefix);
        if (apiRegistry != null) add(result, seen, apiRegistry.namespaces().stream().filter(name -> !name.startsWith("__java_")).toList(), CompletionItem.CompletionKind.NAMESPACE, prefix);
        add(result, seen, JavaImportAliases.resolve(content, javaImportRegistry).keySet().stream().toList(), CompletionItem.CompletionKind.NAMESPACE, prefix);
        if (constantRegistry != null) add(result, seen, constantRegistry.namespaces().stream().toList(), CompletionItem.CompletionKind.NAMESPACE, prefix);
        return List.copyOf(result);
    }

    private static void addAnnotations(List<CompletionItem> result, Set<String> seen, List<String> names, String prefix, String detail, String documentation) {
        for (String name : names) addAnnotation(result, seen, name, prefix, detail, documentation);
    }

    private static void addAnnotation(List<CompletionItem> result, Set<String> seen, String name, String prefix, String detail, String documentation) {
        if (!matches(name, prefix)) return;
        String label = "@" + name;
        if (seen.add(label)) result.add(new CompletionItem(label, detail, documentation, label, CompletionItem.CompletionKind.SNIPPET));
    }

    private static void add(List<CompletionItem> result, Set<String> seen, List<String> values, CompletionItem.CompletionKind kind, String prefix) {
        for (String value : values) if (matches(value, prefix) && seen.add(value)) result.add(CompletionItem.of(value, kind));
    }

    private static boolean matches(String value, String prefix) {
        return prefix.isEmpty() || value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean blockedContext(String content, int line, int column) {
        int offset = cursorOffset(content, line, column);
        if (offset < 0) return true;
        for (var token : new Lexer(content, "completion.vls").lex().tokens()) {
            if (offset < token.offset() || offset >= token.offset() + token.length()) continue;
            if (token.is(TokenType.COMMENT) || token.is(TokenType.STRING) || token.is(TokenType.STRING_INTERP)) return true;
        }
        return false;
    }

    private static int cursorOffset(String content, int line, int column) {
        if (line < 1 || column < 1) return -1;
        int currentLine = 1;
        int lineStart = 0;
        for (int i = 0; i < content.length() && currentLine < line; i++) {
            if (content.charAt(i) == '\n') { currentLine++; lineStart = i + 1; }
        }
        if (currentLine != line) return -1;
        int lineEnd = content.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = content.length();
        if (lineEnd > lineStart && content.charAt(lineEnd - 1) == '\r') lineEnd--;
        return Math.min(lineStart + column - 1, lineEnd);
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

    private static boolean annotationContext(String content, int line, int column, int prefixLength) {
        String[] lines = content.split("\\R", -1);
        if (line < 1 || line > lines.length) return false;
        String source = lines[line - 1];
        int cursor = Math.min(column - 1, source.length()) - prefixLength;
        return cursor > 0 && source.charAt(cursor - 1) == '@';
    }
}
