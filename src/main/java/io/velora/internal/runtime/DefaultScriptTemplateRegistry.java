package io.velora.internal.runtime;

import io.velora.api.script.ScriptTemplate;
import io.velora.api.script.ScriptTemplateRegistry;

import java.util.*;

public final class DefaultScriptTemplateRegistry implements ScriptTemplateRegistry {

    private final Map<String, ScriptTemplate> templates = new LinkedHashMap<>();
    private boolean frozen;

    @Override
    public void register(ScriptTemplate template) {
        checkFrozen();
        Objects.requireNonNull(template.id());
        if (templates.containsKey(template.id())) {
            throw new IllegalStateException("Duplicate template id: " + template.id());
        }
        templates.put(template.id(), template);
    }

    @Override
    public Optional<ScriptTemplate> find(String templateId) {
        return Optional.ofNullable(templates.get(templateId));
    }

    @Override
    public Collection<ScriptTemplate> all() {
        return List.copyOf(templates.values());
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    void freeze() {
        frozen = true;
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("ScriptTemplateRegistry is frozen");
    }
}
