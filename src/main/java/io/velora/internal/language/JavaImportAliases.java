package io.velora.internal.language;

import io.velora.api.interop.JavaImportDescriptor;
import io.velora.api.interop.JavaImportRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavaImportAliases {
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;?\\s*$");

    private JavaImportAliases() { }

    static Map<String, JavaImportDescriptor> resolve(String content, JavaImportRegistry registry) {
        if (registry == null || content == null || content.isEmpty()) return Map.of();
        Map<String, JavaImportDescriptor> result = new LinkedHashMap<>();
        Matcher matcher = IMPORT.matcher(content);
        while (matcher.find()) {
            JavaImportDescriptor descriptor = registry.find(matcher.group(1));
            if (descriptor != null) result.putIfAbsent(descriptor.alias(), descriptor);
        }
        return Map.copyOf(result);
    }

    static String namespace(String content, String alias, JavaImportRegistry registry) {
        JavaImportDescriptor descriptor = resolve(content, registry).get(alias);
        return descriptor != null ? descriptor.namespace() : alias;
    }
}
