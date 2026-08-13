package io.velora.internal.language;

import io.velora.api.language.TextEdit;

import java.util.*;

public final class RenameEngine {

    public static List<TextEdit> rename(String content, String oldName, String newName, String filePath) {
        List<TextEdit> edits = new ArrayList<>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            int col = lines[i].indexOf(oldName);
            while (col >= 0) {
                edits.add(TextEdit.replace(filePath, i + 1, col + 1, oldName.length(), newName));
                col = lines[i].indexOf(oldName, col + oldName.length());
            }
        }
        return edits;
    }
}
