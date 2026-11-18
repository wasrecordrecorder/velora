package io.velora.internal.language;

import io.velora.api.event.EventRegistry;
import io.velora.api.function.ApiRegistry;
import io.velora.api.language.EditorSession;
import io.velora.api.language.LanguageService;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.SettingRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.host.VeloraHost;

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
    private final VeloraHost host;
    private boolean available = true;

    public DefaultLanguageService() {
        this(null, null, null, null, null, null, null);
    }

    public DefaultLanguageService(ApiRegistry apiRegistry, TypeRegistry typeRegistry, EventRegistry eventRegistry,
                                  SettingRegistry settingRegistry, ConstantRegistry constantRegistry) {
        this(apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, null, null);
    }

    public DefaultLanguageService(ApiRegistry apiRegistry, TypeRegistry typeRegistry, EventRegistry eventRegistry,
                                  SettingRegistry settingRegistry, ConstantRegistry constantRegistry,
                                  JavaImportRegistry javaImportRegistry) {
        this(apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, javaImportRegistry, null);
    }

    public DefaultLanguageService(ApiRegistry apiRegistry, TypeRegistry typeRegistry, EventRegistry eventRegistry,
                                  SettingRegistry settingRegistry, ConstantRegistry constantRegistry,
                                  JavaImportRegistry javaImportRegistry, VeloraHost host) {
        this.apiRegistry = apiRegistry;
        this.typeRegistry = typeRegistry;
        this.eventRegistry = eventRegistry;
        this.settingRegistry = settingRegistry;
        this.constantRegistry = constantRegistry;
        this.javaImportRegistry = javaImportRegistry;
        this.host = host;
    }

    @Override
    public EditorSession openEditor(String scriptId, String filePath) {
        if (!available) throw new IllegalStateException("Language service is closed");
        DefaultEditorSession session = new DefaultEditorSession(scriptId, filePath, apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, javaImportRegistry, host);
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
