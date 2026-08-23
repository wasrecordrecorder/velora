package io.velora.host;

import io.velora.api.data.ByteArray;

import java.nio.file.Path;
import java.util.List;

public interface AssetStore {
    boolean exists(String path);

    ByteArray bytes(String path);

    String text(String path);

    List<String> list(String directory);

    static AssetStore disabled() {
        return DisabledAssetStore.INSTANCE;
    }

    static AssetStore local(Path root, Path legacyRoot, long maxAssetBytes, VeloraLogger logger) {
        return new LocalAssetStore(root, legacyRoot, maxAssetBytes, logger);
    }
}
