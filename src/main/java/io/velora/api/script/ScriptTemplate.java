package io.velora.api.script;

import java.util.List;
import java.util.Map;

public record ScriptTemplate(
        String id,
        String name,
        String description,
        Map<String, String> files
) {
    public ScriptTemplate {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(name);
        files = files == null ? Map.of() : Map.copyOf(files);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String name;
        private String description = "";
        private final Map<String, String> files = new java.util.LinkedHashMap<>();

        public Builder(String id) {
            this.id = id;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder file(String path, String content) {
            this.files.put(path, content);
            return this;
        }

        public ScriptTemplate build() {
            return new ScriptTemplate(id, name, description, files);
        }
    }
}
