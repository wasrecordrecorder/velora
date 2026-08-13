package io.velora.internal.bytecode;

import java.util.*;

public final class SourceMap {
    private final int[] offsets;
    private final int[] lineNumbers;

    public SourceMap(int[] offsets, int[] lineNumbers) {
        this.offsets = offsets;
        this.lineNumbers = lineNumbers;
    }

    public int lineForOffset(int codeOffset) {
        int result = -1;
        for (int i = 0; i < offsets.length; i++) {
            if (offsets[i] <= codeOffset) {
                result = lineNumbers[i];
            } else {
                break;
            }
        }
        return result;
    }

    public static SourceMap empty() {
        return new SourceMap(new int[0], new int[0]);
    }
}
