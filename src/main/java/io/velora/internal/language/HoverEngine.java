package io.velora.internal.language;

import io.velora.api.event.EventRegistry;
import io.velora.api.function.ApiRegistry;
import io.velora.api.language.HoverInfo;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;
import io.velora.internal.semantic.ResolvedScript;

import java.util.Map;
import java.util.Optional;

public final class HoverEngine {
    private static final Map<String, String> INFO = Map.ofEntries(
            Map.entry("script", "Declares a Velora script."),
            Map.entry("async", "Marks a function or handler as suspendable."),
            Map.entry("spawn", "Starts a script function in a child fiber and returns Task<T>."),
            Map.entry("await", "Suspends until a Task<T> completes and returns its result."),
            Map.entry("delay", "Suspends the current fiber for a Duration or nanosecond value."),
            Map.entry("yield", "Yields the current fiber until the next scheduler opportunity."),
            Map.entry("list", "Creates an empty typed list, for example list<Player>()."),
            Map.entry("set", "Creates an empty typed set, for example set<UUID>()."),
            Map.entry("map", "Creates an empty typed map, for example map<String, int>()."),
            Map.entry("return", "Returns a value from the current function."),
            Map.entry("Duration", "A duration value. Literals include 500.ms, 2.seconds and 1.minute."),
            Map.entry("Task", "A handle to a spawned script computation: Task<T>."),
            Map.entry("Script", "Declares script metadata. @Script is required."),
            Map.entry("Version", "Optional script version metadata."),
            Map.entry("Author", "Optional script author metadata."),
            Map.entry("Description", "Optional script description metadata."),
            Map.entry("Setting", "Exposes the annotated field as a client setting."),
            Map.entry("Persistent", "Persists the annotated field across script reloads."),
            Map.entry("Load", "Runs the annotated function when the script is loaded."),
            Map.entry("Enable", "Runs the annotated function when the script is enabled."),
            Map.entry("Run", "Uses the annotated function as the script run entry."),
            Map.entry("Disable", "Runs the annotated function when the script is disabled."),
            Map.entry("Unload", "Runs the annotated function when the script is unloaded.")
    );

    private HoverEngine() {}

    public static Optional<HoverInfo> getHover(String content, int line, int column, String filePath) {
        return getHover(content, line, column, filePath, null, null, null, null, null, null);
    }

    public static Optional<HoverInfo> getHover(String content, int line, int column, String filePath, ApiRegistry apiRegistry,
                                               TypeRegistry typeRegistry, ConstantRegistry constantRegistry, EventRegistry eventRegistry) {
        return getHover(content, line, column, filePath, apiRegistry, typeRegistry, constantRegistry, eventRegistry, null, null);
    }

    public static Optional<HoverInfo> getHover(String content, int line, int column, String filePath, ApiRegistry apiRegistry,
                                               TypeRegistry typeRegistry, ConstantRegistry constantRegistry, EventRegistry eventRegistry,
                                               JavaImportRegistry javaImportRegistry) {
        return getHover(content, line, column, filePath, apiRegistry, typeRegistry, constantRegistry, eventRegistry, javaImportRegistry, null);
    }

    public static Optional<HoverInfo> getHover(String content, int line, int column, String filePath, ApiRegistry apiRegistry,
                                               TypeRegistry typeRegistry, ConstantRegistry constantRegistry, EventRegistry eventRegistry,
                                               JavaImportRegistry javaImportRegistry, ResolvedScript resolved) {
        if (line < 1 || column < 1) return Optional.empty();
        int zeroColumn = column - 1;
        for (Token token : new Lexer(content, filePath).lex().tokens()) {
            if (token.line() != line || token.length() == 0) continue;
            if (zeroColumn < token.column() || zeroColumn >= token.column() + token.length()) continue;
            if (token.isTrivia() || token.is(TokenType.EOF)) return Optional.empty();
            String text = token.text().startsWith("@") ? token.text().substring(1) : token.text();
            String qualified = qualifiedAt(content, line, token.column(), token.text());
            String detail = dynamicInfo(content, text, qualified, token.offset(), apiRegistry, typeRegistry, constantRegistry, eventRegistry, javaImportRegistry, resolved);
            if (detail == null) detail = INFO.get(text);
            String shown = qualified != null ? qualified : token.text();
            String body = "```velora\n" + shown + "\n```" + (detail == null ? "" : "\n" + detail);
            return Optional.of(HoverInfo.of(body, filePath, token.line(), token.column() + 1));
        }
        return Optional.empty();
    }

    private static String dynamicInfo(String content, String token, String qualified, int tokenOffset, ApiRegistry apiRegistry, TypeRegistry typeRegistry,
                                      ConstantRegistry constantRegistry, EventRegistry eventRegistry, JavaImportRegistry javaImportRegistry, ResolvedScript resolved) {
        if (qualified != null) {
            String normalized = qualified.replace("?.", ".");
            int dot = normalized.lastIndexOf('.');
            String namespace = normalized.substring(0, dot);
            String member = normalized.substring(dot + 1);
            if (apiRegistry != null && namespace.indexOf('.') < 0) {
                var function = apiRegistry.find(JavaImportAliases.namespace(content, namespace, javaImportRegistry), member);
                if (function != null) return "`" + LanguageMetadata.signature(function) + "`" + (function.description().isBlank() ? "" : "\n" + function.description());
            }
            if (constantRegistry != null && namespace.indexOf('.') < 0) {
                var constant = constantRegistry.find(namespace, member);
                if (constant != null) return "Constant `" + constant.type().name() + "`.";
            }
            var receiverType = LanguageMetadata.qualifierType(content, namespace, tokenOffset, typeRegistry, resolved);
            var builtin = LanguageMetadata.member(receiverType, member);
            if (builtin != null) return "`" + builtin.detail() + "`\n" + builtin.description();
        }
        if (resolved != null) {
            var property = resolved.properties().get(token);
            if (property != null) return (property.isConst() ? "Constant" : "Property") + " `" + property.type().name() + "`." + (property.persistent() ? " Persistent." : "");
            for (var setting : resolved.settings()) if (setting.id().equals(token)) return "Setting `" + setting.type().name() + "`." + setting.description().filter(value -> !value.isBlank()).map(value -> "\n" + value).orElse("");
            var function = resolved.functions().get(token);
            if (function != null) return "Function `" + function.name() + "(" + String.join(", ", function.parameters().stream().map(parameter -> parameter.name() + ": " + parameter.type().name()).toList()) + ") -> " + function.returnType().name() + "`.";
        }
        if (javaImportRegistry != null) {
            var imported = JavaImportAliases.resolve(content, javaImportRegistry).get(token);
            if (imported != null) return "Java import `" + imported.importName() + "`." + (imported.description().isBlank() ? "" : "\n" + imported.description());
        }
        if (eventRegistry != null) {
            for (var event : eventRegistry.all()) {
                if (event.scriptName().equals(token)) return "Client event `" + event.scriptName() + "` with payload `" + event.payloadType().name() + "`." + (event.description() == null ? "" : "\n" + event.description());
            }
        }
        String builtInTypeDocumentation = LanguageMetadata.typeDocumentation(token);
        if (builtInTypeDocumentation != null) return builtInTypeDocumentation;
        if (typeRegistry != null && typeRegistry.find(token) != null) {
            String documentation = LanguageMetadata.typeDocumentation(typeRegistry.find(token).nonNull().name());
            return documentation != null ? documentation : "Registered Velora type `" + typeRegistry.find(token).name() + "`.";
        }
        return null;
    }

    private static String qualifiedAt(String content, int line, int tokenColumn, String token) {
        if (token.startsWith("@")) return null;
        String[] lines = content.split("\\R", -1);
        if (line < 1 || line > lines.length || tokenColumn <= 0) return null;
        String source = lines[line - 1];
        int dot = tokenColumn - 1;
        while (dot >= 0 && Character.isWhitespace(source.charAt(dot))) dot--;
        if (dot < 0 || source.charAt(dot) != '.') return null;
        int end = dot;
        int start = end;
        while (start > 0) {
            char c = source.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.' || c == '?') start--;
            else break;
        }
        if (start == end) return null;
        String qualifier = source.substring(start, end);
        if (qualifier.startsWith(".") || qualifier.endsWith(".")) return null;
        return qualifier + "." + token;
    }
}
