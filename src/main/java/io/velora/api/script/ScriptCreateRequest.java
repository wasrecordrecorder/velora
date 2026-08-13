package io.velora.api.script;

import java.util.List;
import java.util.Map;

public record ScriptCreateRequest(
        String scriptId,
        String name,
        String templateId,
        Map<String, String> initialFiles,
        Map<String, Object> initialSettings
) {
    public ScriptCreateRequest {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(name);
        initialFiles = initialFiles == null ? Map.of() : Map.copyOf(initialFiles);
        initialSettings = initialSettings == null ? Map.of() : Map.copyOf(initialSettings);
    }

    public static Builder builder(String scriptId, String name) {
        return new Builder(scriptId, name);
    }

    public static final class Builder {
        private final String scriptId;
        private final String name;
        private String templateId;
        private final java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Object> settings = new java.util.LinkedHashMap<>();

        public Builder(String scriptId, String name) {
            this.scriptId = scriptId;
            this.name = name;
        }

        public Builder template(String templateId) {
            this.templateId = templateId;
            return this;
        }

        public Builder file(String path, String content) {
            this.files.put(path, content);
            return this;
        }

        public Builder setting(String id, Object value) {
            this.settings.put(id, value);
            return this;
        }

        public ScriptCreateRequest build() {
            return new ScriptCreateRequest(scriptId, name, templateId, files, settings);
        }
    }
}
