package io.velora.api.language;

public interface LanguageService {

    EditorSession openEditor(String scriptId, String filePath);

    boolean isAvailable();

    void close();
}
