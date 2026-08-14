package io.velora.api.compiler;

import java.util.List;
import java.util.Map;

public record CompileRequest(
        String scriptId,
        List<SourceFile> sources,
        CompileMode mode,
        int languageVersion,
        Map<String, String> options
) {
    public CompileRequest {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(sources);
        sources = List.copyOf(sources);
        mode = mode == null ? CompileMode.FULL : mode;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static Builder builder(String scriptId) {
        return new Builder(scriptId);
    }

    public static final class Builder {
        private final String scriptId;
        private final List<SourceFile> sources = new java.util.ArrayList<>();
        private CompileMode mode = CompileMode.FULL;
        private int languageVersion = 2;
        private final Map<String, String> options = new java.util.LinkedHashMap<>();

        public Builder(String scriptId) {
            this.scriptId = scriptId;
        }

        public Builder source(SourceFile file) {
            this.sources.add(file);
            return this;
        }

        public Builder source(String path, String content) {
            this.sources.add(SourceFile.of(path, content));
            return this;
        }

        public Builder sources(List<SourceFile> files) {
            this.sources.addAll(files);
            return this;
        }

        public Builder mode(CompileMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder languageVersion(int version) {
            this.languageVersion = version;
            return this;
        }

        public Builder option(String key, String value) {
            this.options.put(key, value);
            return this;
        }

        public CompileRequest build() {
            return new CompileRequest(scriptId, sources, mode, languageVersion, options);
        }
    }
}
