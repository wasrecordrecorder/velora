package io.velora.internal.persistence;

import io.velora.api.setting.SettingValue;

import java.util.*;

public final class SettingsFileCodec {

    public static String encode(Map<String, SettingValue> values) {
        StringBuilder sb = new StringBuilder();
        for (var e : values.entrySet()) {
            sb.append(e.getKey()).append('=');
            Object v = e.getValue().value();
            if (v instanceof String s) {
                sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            } else {
                sb.append(v);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static Map<String, SettingValue> decode(String content) {
        Map<String, SettingValue> result = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            Object parsed;
            if (val.startsWith("\"") && val.endsWith("\"")) {
                parsed = val.substring(1, val.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            } else if (val.equals("true") || val.equals("false")) {
                parsed = Boolean.parseBoolean(val);
            } else {
                try { parsed = Integer.parseInt(val); }
                catch (NumberFormatException e1) {
                    try { parsed = Double.parseDouble(val); }
                    catch (NumberFormatException e2) { parsed = val; }
                }
            }
            result.put(key, inferSettingValue(parsed));
        }
        return result;
    }

    private static SettingValue inferSettingValue(Object parsed) {
        if (parsed instanceof Integer i) return SettingValue.ofInt(i);
        if (parsed instanceof Boolean b) return SettingValue.ofBoolean(b);
        if (parsed instanceof Double d) return SettingValue.ofDouble(d);
        return SettingValue.ofString(String.valueOf(parsed));
    }
}
