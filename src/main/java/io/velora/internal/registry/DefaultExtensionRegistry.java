package io.velora.internal.registry;

import io.velora.api.VeloraExtension;
import io.velora.api.VeloraExtensionRegistry;

import java.util.*;

public final class DefaultExtensionRegistry implements VeloraExtensionRegistry {

    private final List<VeloraExtension> extensions = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private boolean frozen;

    @Override
    public void register(VeloraExtension extension) {
        checkFrozen();
        Objects.requireNonNull(extension.id());
        if (ids.contains(extension.id())) {
            throw new IllegalStateException("Duplicate extension id: " + extension.id());
        }
        ids.add(extension.id());
        extensions.add(extension);
    }

    @Override
    public Collection<VeloraExtension> extensions() {
        return List.copyOf(extensions);
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    public void freeze() {
        frozen = true;
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("ExtensionRegistry is frozen");
    }
}
