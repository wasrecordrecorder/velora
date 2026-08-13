package io.velora;

import io.velora.api.setting.SettingValue;
import io.velora.internal.persistence.SettingsFileCodec;
import io.velora.internal.persistence.StateFileCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceV2Test {

    // === SettingsFileCodec ===

    @Test
    @DisplayName("SettingsFileCodec: int round-trip")
    void settingsCodec_int() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        original.put("radius", SettingValue.ofInt(32));
        String encoded = SettingsFileCodec.encode(original);
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("SettingsFileCodec: boolean round-trip")
    void settingsCodec_boolean() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        original.put("enabled", SettingValue.ofBoolean(true));
        String encoded = SettingsFileCodec.encode(original);
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("SettingsFileCodec: double round-trip")
    void settingsCodec_double() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        original.put("distance", SettingValue.ofDouble(12.5));
        String encoded = SettingsFileCodec.encode(original);
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("SettingsFileCodec: string round-trip")
    void settingsCodec_string() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        original.put("target", SettingValue.ofString("diamond_ore"));
        String encoded = SettingsFileCodec.encode(original);
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("SettingsFileCodec: multiple settings round-trip")
    void settingsCodec_multiple() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        original.put("radius", SettingValue.ofInt(32));
        original.put("enabled", SettingValue.ofBoolean(true));
        original.put("distance", SettingValue.ofDouble(12.5));
        original.put("target", SettingValue.ofString("ore"));
        String encoded = SettingsFileCodec.encode(original);
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("SettingsFileCodec: string with special characters")
    void settingsCodec_specialChars() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        original.put("path", SettingValue.ofString("C:\\test\\file.txt"));
        String encoded = SettingsFileCodec.encode(original);
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("SettingsFileCodec: string with quotes")
    void settingsCodec_quotes() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        original.put("msg", SettingValue.ofString("say \"hello\""));
        String encoded = SettingsFileCodec.encode(original);
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("SettingsFileCodec: Unicode string round-trip")
    void settingsCodec_unicode() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        original.put("label", SettingValue.ofString("Радиус Ω"));
        String encoded = SettingsFileCodec.encode(original);
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("SettingsFileCodec: empty map")
    void settingsCodec_empty() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        String encoded = SettingsFileCodec.encode(original);
        assertTrue(encoded.isEmpty());
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertTrue(decoded.isEmpty());
    }

    @Test
    @DisplayName("SettingsFileCodec: decode ignores comments")
    void settingsCodec_comments() {
        String content = "# This is a comment\nradius=32\n# Another comment\n";
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(content);
        assertEquals(1, decoded.size());
        assertEquals(32, decoded.get("radius").asInt());
    }

    @Test
    @DisplayName("SettingsFileCodec: decode ignores empty lines")
    void settingsCodec_emptyLines() {
        String content = "\n\nradius=32\n\n";
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(content);
        assertEquals(1, decoded.size());
    }

    @Test
    @DisplayName("SettingsFileCodec: negative int")
    void settingsCodec_negativeInt() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        original.put("offset", SettingValue.ofInt(-42));
        String encoded = SettingsFileCodec.encode(original);
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("SettingsFileCodec: zero values")
    void settingsCodec_zero() {
        Map<String, SettingValue> original = new LinkedHashMap<>();
        original.put("zero_int", SettingValue.ofInt(0));
        original.put("zero_double", SettingValue.ofDouble(0.0));
        original.put("false_bool", SettingValue.ofBoolean(false));
        String encoded = SettingsFileCodec.encode(original);
        Map<String, SettingValue> decoded = SettingsFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    // === StateFileCodec ===

    @Test
    @DisplayName("StateFileCodec: int round-trip")
    void stateCodec_int() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("counter", 42);
        String encoded = StateFileCodec.encode(original);
        Map<String, Object> decoded = StateFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("StateFileCodec: boolean round-trip")
    void stateCodec_boolean() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("flag", true);
        String encoded = StateFileCodec.encode(original);
        Map<String, Object> decoded = StateFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("StateFileCodec: double round-trip")
    void stateCodec_double() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("distance", 3.14);
        String encoded = StateFileCodec.encode(original);
        Map<String, Object> decoded = StateFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("StateFileCodec: string round-trip")
    void stateCodec_string() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("name", "test");
        String encoded = StateFileCodec.encode(original);
        Map<String, Object> decoded = StateFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("StateFileCodec: null round-trip")
    void stateCodec_null() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("value", null);
        String encoded = StateFileCodec.encode(original);
        Map<String, Object> decoded = StateFileCodec.decode(encoded);
        assertNull(decoded.get("value"));
    }

    @Test
    @DisplayName("StateFileCodec: mixed types round-trip")
    void stateCodec_mixed() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("count", 42);
        original.put("enabled", true);
        original.put("rate", 3.14);
        original.put("label", "test");
        original.put("ref", null);
        String encoded = StateFileCodec.encode(original);
        Map<String, Object> decoded = StateFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("StateFileCodec: empty map")
    void stateCodec_empty() {
        Map<String, Object> original = new LinkedHashMap<>();
        String encoded = StateFileCodec.encode(original);
        assertTrue(encoded.isEmpty());
        Map<String, Object> decoded = StateFileCodec.decode(encoded);
        assertTrue(decoded.isEmpty());
    }

    @Test
    @DisplayName("StateFileCodec: decode ignores comments")
    void stateCodec_comments() {
        String content = "# comment\ncount=42\n";
        Map<String, Object> decoded = StateFileCodec.decode(content);
        assertEquals(1, decoded.size());
        assertEquals(42, decoded.get("count"));
    }

    @Test
    @DisplayName("StateFileCodec: negative int")
    void stateCodec_negativeInt() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("offset", -100);
        String encoded = StateFileCodec.encode(original);
        Map<String, Object> decoded = StateFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("StateFileCodec: large int")
    void stateCodec_largeInt() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("big", 2147483647);
        String encoded = StateFileCodec.encode(original);
        Map<String, Object> decoded = StateFileCodec.decode(encoded);
        assertEquals(original, decoded);
    }
}
