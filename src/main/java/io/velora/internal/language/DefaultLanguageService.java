package io.velora.internal.language;

import io.velora.api.language.EditorSession;
import io.velora.api.language.LanguageService;

import java.util.*;

public final class DefaultLanguageService implements LanguageService {

    private final Map<String, DefaultEditorSession> sessions = new HashMap<>();
    private boolean available = true;

    @Override
    public EditorSession openEditor(String scriptId, String filePath) {
        if (!available) throw new IllegalStateException("Language service is closed");
        DefaultEditorSession session = new DefaultEditorSession(scriptId, filePath);
        DefaultEditorSession previous = sessions.put(scriptId + ":" + filePath, session);
        if (previous != null) previous.close();
        return session;
    }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public void close() {
        for (DefaultEditorSession s : sessions.values()) s.close();
        sessions.clear();
        available = false;
    }
}
