package io.velora.api.script;

import java.util.Collection;
import java.util.Optional;

public interface ScriptTemplateRegistry {

    void register(ScriptTemplate template);

    Optional<ScriptTemplate> find(String templateId);

    Collection<ScriptTemplate> all();

    boolean isFrozen();
}
