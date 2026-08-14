package io.velora.internal.script;

import io.velora.api.script.ScriptDescriptor;
import io.velora.api.script.ScriptStatus;

import java.util.*;

public final class ScriptRepository {
    private final Map<String, ScriptInstance> instances = new LinkedHashMap<>();
    private final Map<String, ScriptDescriptor> descriptors = new LinkedHashMap<>();

    public void register(ScriptInstance instance) {
        instances.put(instance.scriptId(), instance);
        descriptors.put(instance.scriptId(), instance.descriptor());
    }

    public ScriptInstance get(String scriptId) {
        return instances.get(scriptId);
    }

    public ScriptDescriptor descriptor(String scriptId) {
        return descriptors.get(scriptId);
    }

    public void remove(String scriptId) {
        instances.remove(scriptId);
        descriptors.remove(scriptId);
    }

    public Collection<ScriptInstance> all() {
        return Collections.unmodifiableCollection(instances.values());
    }

    public List<ScriptDescriptor> descriptors() {
        List<ScriptDescriptor> result = new ArrayList<>();
        for (ScriptInstance instance : instances.values()) {
            ScriptDescriptor d = instance.descriptor();
            result.add(new ScriptDescriptor(
                d.id(), d.name(), d.version(), d.author(), d.description(),
                instance.status(), instance.enabled(),
                d.sourceFiles(), d.activeRevision(),
                d.errorCount(), d.warningCount(), d.lastReloadTimeNanos()
            ));
        }
        return result;
    }

    public boolean contains(String scriptId) {
        return instances.containsKey(scriptId);
    }

    public int size() { return instances.size(); }
}
