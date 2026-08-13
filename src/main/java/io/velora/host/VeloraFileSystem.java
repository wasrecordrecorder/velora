package io.velora.host;

import java.util.List;

public interface VeloraFileSystem {
    List<ScriptFileEntry> listScripts();

    SourceSnapshot readSource(String scriptId, String relativePath);

    FileRevision writeAtomic(String scriptId, String relativePath, String content, FileRevision expectedRevision);

    FileTransaction beginTransaction(String scriptId);

    byte[] readData(String scriptId, String key);

    void writeDataAtomic(String scriptId, String key, byte[] data);

    boolean scriptExists(String scriptId);

    void deleteScript(String scriptId);
}
