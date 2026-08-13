package io.velora.internal.source;

public final class LineMap {

    private final int[] lineStarts;

    private LineMap(int[] lineStarts) {
        this.lineStarts = lineStarts;
    }

    public static LineMap of(String content) {
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        starts.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            arr[i] = starts.get(i);
        }
        return new LineMap(arr);
    }

    public int lineCount() {
        return lineStarts.length;
    }

    public int lineStartOffset(int line) {
        if (line < 1 || line > lineStarts.length) {
            return 0;
        }
        return lineStarts[line - 1];
    }

    public int offsetOf(int line, int column) {
        return lineStartOffset(line) + Math.max(0, column - 1);
    }

    public int lineOf(int offset) {
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }

    public int columnOf(int offset) {
        int line = lineOf(offset);
        return offset - lineStartOffset(line) + 1;
    }

    public String lineText(String content, int line) {
        if (line < 1 || line > lineStarts.length) {
            return "";
        }
        int start = lineStarts[line - 1];
        int end = line < lineStarts.length ? lineStarts[line] - 1 : content.length();
        if (end > start && content.charAt(end - 1) == '\r') {
            end--;
        }
        return content.substring(start, end);
    }
}
