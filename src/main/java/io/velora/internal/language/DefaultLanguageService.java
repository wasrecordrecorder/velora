package io.velora.internal.language;

import io.velora.api.event.EventRegistry;
import io.velora.api.function.ApiRegistry;
import io.velora.api.language.EditorSession;
import io.velora.api.language.LanguageService;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.SettingRegistry;
import io.velora.api.registry.TypeRegistry;

import java.util.HashMap;
import java.util.Map;

public final class DefaultLanguageService implements LanguageService {
    private final Map<String, DefaultEditorSession> sessions = new HashMap<>();
    private final ApiRegistry apiRegistry;
    private final TypeRegistry typeRegistry;
    private final EventRegistry eventRegistry;
    private final SettingRegistry settingRegistry;
    private final ConstantRegistry constantRegistry;
    private final JavaImportRegistry javaImportRegistry;
    private boolean available = true;

    public DefaultLanguageService() {
        this(null, null, null, null, null, null);
    }

    public DefaultLanguageService(ApiRegistry apiRegistry, TypeRegistry typeRegistry, EventRegistry eventRegistry,
                                  SettingRegistry settingRegistry, ConstantRegistry constantRegistry) {
        this(apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, null);
    }

    public DefaultLanguageService(ApiRegistry apiRegistry, TypeRegistry typeRegistry, EventRegistry eventRegistry,
                                  SettingRegistry settingRegistry, ConstantRegistry constantRegistry,
                                  JavaImportRegistry javaImportRegistry) {
        this.apiRegistry = apiRegistry;
        this.typeRegistry = typeRegistry;
        this.eventRegistry = eventRegistry;
        this.settingRegistry = settingRegistry;
        this.constantRegistry = constantRegistry;
        this.javaImportRegistry = javaImportRegistry;
    }

    @Override
    public EditorSession openEditor(String scriptId, String filePath) {
        if (!available) throw new IllegalStateException("Language service is closed");
        DefaultEditorSession session = new DefaultEditorSession(scriptId, filePath, apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, javaImportRegistry);
        DefaultEditorSession previous = sessions.put(scriptId + ":" + filePath, session);
        if (previous != null) previous.close();
        return session;
    }

    @Override public boolean isAvailable() { return available; }

    @Override
    public void close() {
        for (DefaultEditorSession session : sessions.values()) session.close();
        sessions.clear();
        available = false;
    }
}
