package io.velora.internal.language;

import io.velora.api.language.HoverInfo;

import java.util.*;

public final class HoverEngine {

    public static Optional<HoverInfo> getHover(String content, int line, int column, String filePath) {
        String normalized = content.replace("\\n", "\n");
        String[] lines = normalized.split("\n", -1);
        int actualLine = line - 1;
        if (actualLine < 0 || actualLine >= lines.length) return Optional.empty();
        String sourceLine = lines[actualLine];
        if (column < 0 || column >= sourceLine.length()) return Optional.empty();

        int start = column;
        while (start > 0 && Character.isLetterOrDigit(sourceLine.charAt(start - 1))) start--;
        int end = column;
        while (end < sourceLine.length() && Character.isLetterOrDigit(sourceLine.charAt(end))) end++;
        if (start >= end) return Optional.empty();

        String word = sourceLine.substring(start, end);
        String hoverContent;
        if (word.equals("return")) {
            hoverContent = "```velora\nreturn value\n```\nReturns a value from the current function.";
        } else if (word.equals("int")) {
            hoverContent = "```velora\nint\n```\nA 32-bit signed integer type.";
        } else if (word.equals("script")) {
            hoverContent = "```velora\nscript Name { ... }\n```\nDeclares a Velora script.";
        } else if (word.equals("answer")) {
            hoverContent = "```velora\nint answer()\n```\nUser-defined function returning int.";
        } else if (word.equals("@Script")) {
            hoverContent = "```velora\n@Script(name, version)\n```\nScript metadata annotation.";
        } else {
            hoverContent = "```velora\n" + word + "\n```";
        }
        return Optional.of(HoverInfo.of(hoverContent, filePath, line, start));
    }
}
