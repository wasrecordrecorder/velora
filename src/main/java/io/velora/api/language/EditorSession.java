package io.velora.api.language;

import java.util.List;
import java.util.Optional;

public interface EditorSession extends AutoCloseable {

    String scriptId();

    String filePath();

    void updateText(String text);

    EditorSnapshot snapshot();

    List<CompletionItem> completions(int line, int column);

    Optional<HoverInfo> hover(int line, int column);

    Optional<SignatureHelp> signatureHelp(int line, int column);

    Optional<DefinitionLocation> definition(int line, int column);

    List<TextEdit> format();

    List<TextEdit> rename(String oldName, String newName);

    @Override
    void close();
}
