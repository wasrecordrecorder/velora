package io.velora.internal.language;

import io.velora.api.language.SignatureHelp;

import java.util.List;
import java.util.Optional;

public final class SignatureHelpEngine {
    private SignatureHelpEngine() {}

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column) {
        if (line < 1 || column < 1) return Optional.empty();
        String[] lines = content.split("\\R", -1);
        int lineIndex = line - 1;
        if (lineIndex >= lines.length) return Optional.empty();

        int parenDepth = 0;
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
                        return name.isEmpty() ? Optional.empty() : Optional.of(buildSignature(name));
                    }
                    parenDepth--;
                }
            }
        }
        return Optional.empty();
    }

    private static SignatureHelp buildSignature(String name) {
        if (name.equals("@Script") || name.equals("Script")) {
            return SignatureHelp.of("@Script", List.of(
                    new SignatureHelp.SignatureParameter("name", "String", "Script display name"),
                    new SignatureHelp.SignatureParameter("version", "String", "Script version")
            ));
        }
        if (name.equals("delay")) return SignatureHelp.of("delay", List.of(new SignatureHelp.SignatureParameter("duration", "Duration", "Suspend duration")));
        if (name.equals("await")) return SignatureHelp.of("await", List.of(new SignatureHelp.SignatureParameter("task", "Task<T>", "Task to await")));
        return SignatureHelp.of(name, List.of());
    }
}
