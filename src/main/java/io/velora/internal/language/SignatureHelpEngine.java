package io.velora.internal.language;

import io.velora.api.function.ApiRegistry;
import io.velora.api.language.SignatureHelp;
import io.velora.api.interop.JavaImportRegistry;

import java.util.List;
import java.util.Optional;

public final class SignatureHelpEngine {
    private SignatureHelpEngine() {}

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column) {
        return getSignatureHelp(content, line, column, null, null);
    }

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column, ApiRegistry apiRegistry) {
        return getSignatureHelp(content, line, column, apiRegistry, null);
    }

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column, ApiRegistry apiRegistry,
                                                            JavaImportRegistry javaImportRegistry) {
        if (line < 1 || column < 1) return Optional.empty();
        String[] lines = content.split("\\R", -1);
        int lineIndex = line - 1;
        if (lineIndex >= lines.length) return Optional.empty();
        int parenDepth = 0;
        int commas = 0;
        for (int i = lineIndex; i >= 0; i--) {
            String source = lines[i];
            int scanEnd = i == lineIndex ? Math.min(column - 1, source.length()) : source.length();
            for (int j = scanEnd - 1; j >= 0; j--) {
                char c = source.charAt(j);
                if (c == ')') parenDepth++;
                else if (c == '(') {
                    if (parenDepth == 0) {
                        int end = j;
                        while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) end--;
                        int start = end;
                        while (start > 0 && (Character.isJavaIdentifierPart(source.charAt(start - 1)) || source.charAt(start - 1) == '@' || source.charAt(start - 1) == '.')) start--;
                        String name = source.substring(start, end);
                        return name.isEmpty() ? Optional.empty() : Optional.of(buildSignature(content, name, commas, apiRegistry, javaImportRegistry));
                    }
                    parenDepth--;
                } else if (c == ',' && parenDepth == 0) commas++;
            }
        }
        return Optional.empty();
    }

    private static SignatureHelp buildSignature(String content, String name, int activeParameter, ApiRegistry apiRegistry, JavaImportRegistry javaImportRegistry) {
        if (apiRegistry != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot + 1 < name.length()) {
                var descriptor = apiRegistry.find(JavaImportAliases.namespace(content, name.substring(0, dot), javaImportRegistry), name.substring(dot + 1));
                if (descriptor != null) {
                    List<SignatureHelp.SignatureParameter> parameters = descriptor.parameters().stream()
                            .map(parameter -> new SignatureHelp.SignatureParameter(parameter.name(), parameter.type().name(), parameter.hasDefault() ? "Default: " + parameter.defaultValue() : null))
                            .toList();
                    return new SignatureHelp(descriptor.qualifiedName(), parameters, Math.min(activeParameter, Math.max(0, parameters.size() - 1)), descriptor.description());
                }
            }
        }
        if (name.equals("@Script") || name.equals("Script")) {
            return new SignatureHelp("@Script", List.of(
                    new SignatureHelp.SignatureParameter("name", "String", "Script display name"),
                    new SignatureHelp.SignatureParameter("version", "String", "Script version")
            ), Math.min(activeParameter, 1), null);
        }
        if (name.equals("delay")) return new SignatureHelp("delay", List.of(new SignatureHelp.SignatureParameter("duration", "Duration", "Suspend duration")), 0, null);
        if (name.equals("await")) return new SignatureHelp("await", List.of(new SignatureHelp.SignatureParameter("task", "Task<T>", "Task to await")), 0, null);
        return new SignatureHelp(name, List.of(), 0, null);
    }
}
