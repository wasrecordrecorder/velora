package io.velora.internal.runtime;

import java.util.*;
import java.util.function.Consumer;

public final class ScriptServiceEventBus {
    private final List<Consumer<String>> enableListeners = new ArrayList<>();
    private final List<Consumer<String>> disableListeners = new ArrayList<>();
    private final List<Consumer<String>> reloadListeners = new ArrayList<>();
    private final List<Consumer<String>> errorListeners = new ArrayList<>();

    public void onEnable(Consumer<String> listener) { enableListeners.add(listener); }
    public void onDisable(Consumer<String> listener) { disableListeners.add(listener); }
    public void onReload(Consumer<String> listener) { reloadListeners.add(listener); }
    public void onError(Consumer<String> listener) { errorListeners.add(listener); }

    public void fireEnable(String scriptId) { enableListeners.forEach(l -> l.accept(scriptId)); }
    public void fireDisable(String scriptId) { disableListeners.forEach(l -> l.accept(scriptId)); }
    public void fireReload(String scriptId) { reloadListeners.forEach(l -> l.accept(scriptId)); }
    public void fireError(String scriptId) { errorListeners.forEach(l -> l.accept(scriptId)); }
}
