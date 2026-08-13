package io.velora.internal.language;

import io.velora.api.language.SignatureHelp;

import java.util.*;

public final class SignatureHelpEngine {

    public static Optional<SignatureHelp> getSignatureHelp(String content, int line, int column) {
        String[] lines = content.split("\n", -1);
        if (line < 0 || line >= lines.length) return Optional.empty();

        int parenDepth = 0;
        String name = null;
        for (int i = line; i >= 0; i--) {
            String l = lines[i];
            int scanEnd = (i == line) ? column : l.length();
            for (int j = scanEnd - 1; j >= 0; j--) {
                char c = l.charAt(j);
                if (c == ')') parenDepth++;
                else if (c == '(') {
                    if (parenDepth == 0) {
                        int ws = j - 1;
                        while (ws >= 0 && Character.isWhitespace(l.charAt(ws))) ws--;
                        int wsEnd = ws + 1;
                        while (ws >= 0 && (Character.isLetterOrDigit(l.charAt(ws)) || l.charAt(ws) == '@' || l.charAt(ws) == '.')) ws--;
                        name = l.substring(ws + 1, wsEnd).trim();
                        if (name.isEmpty()) name = "function";
                        return Optional.of(buildSignature(name));
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
        return SignatureHelp.of(name, List.of());
    }
}
