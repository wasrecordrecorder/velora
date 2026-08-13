package io.velora.internal.persistence;

import java.util.*;

public final class StateFileCodec {

    public static String encode(Map<String, Object> state) {
        StringBuilder sb = new StringBuilder();
        for (var e : state.entrySet()) {
            sb.append(e.getKey()).append('=').append(Objects.toString(e.getValue(), "null")).append('\n');
        }
        return sb.toString();
    }

    public static Map<String, Object> decode(String content) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            if (val.equals("null")) {
                result.put(key, null);
            } else if (val.equals("true") || val.equals("false")) {
                result.put(key, Boolean.parseBoolean(val));
            } else {
                try { result.put(key, Integer.parseInt(val)); }
                catch (NumberFormatException e1) {
                    try { result.put(key, Double.parseDouble(val)); }
                    catch (NumberFormatException e2) { result.put(key, val); }
                }
            }
        }
        return result;
    }
}
