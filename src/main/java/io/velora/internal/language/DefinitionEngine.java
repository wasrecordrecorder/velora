package io.velora.internal.language;

import io.velora.api.language.DefinitionLocation;

import java.util.*;

public final class DefinitionEngine {

    public static Optional<DefinitionLocation> getDefinition(String content, int line, int column, String filePath) {
        String[] lines = content.split("\n", -1);
        if (line < 0 || line >= lines.length) return Optional.empty();
        String targetLine = lines[line];

        int start = column;
        while (start > 0 && Character.isJavaIdentifierPart(targetLine.charAt(start - 1))) start--;
        int end = column;
        while (end < targetLine.length() && Character.isJavaIdentifierPart(targetLine.charAt(end))) end++;

        if (start >= end) return Optional.empty();
        String identifier = targetLine.substring(start, end);
        if (identifier.isEmpty()) return Optional.empty();

        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            int idx = findFunctionDefinition(l, identifier);
            if (idx >= 0) {
                return Optional.of(DefinitionLocation.of(filePath, i + 1, idx + 1));
            }
        }

        return Optional.empty();
    }

    private static int findFunctionDefinition(String line, String name) {
        String pattern = name + "(";
        int searchFrom = 0;
        while (true) {
            int idx = line.indexOf(pattern, searchFrom);
            if (idx < 0) return -1;
            if (idx > 0 && Character.isJavaIdentifierPart(line.charAt(idx - 1))) {
                searchFrom = idx + 1;
                continue;
            }
            int typeEnd = idx - 1;
            while (typeEnd > 0 && Character.isWhitespace(line.charAt(typeEnd))) typeEnd--;
            int typeStart = typeEnd;
            while (typeStart > 0 && Character.isJavaIdentifierPart(line.charAt(typeStart - 1))) typeStart--;
            if (typeStart < typeEnd) {
                String typeWord = line.substring(typeStart, typeEnd + 1);
                if (isTypeName(typeWord)) {
                    return idx;
                }
            }
            searchFrom = idx + 1;
        }
    }

    private static boolean isTypeName(String word) {
        return Set.of("int","long","double","float","boolean","String","void","Unit","Vec3","Vec2",
                "Byte","Char","Short","List","Map","Set","Duration","BlockPos","Rotation","Color",
                "Key","UUID","Identifier","BlockId","ItemId","EntityTypeId","PlayerRef","BlockRef",
                "TickEvent","ChatMessageEvent","task","Task").contains(word);
    }
}
