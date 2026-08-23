package io.velora.host;

import io.velora.api.data.ByteArray;

import java.util.List;

final class DisabledAssetStore implements AssetStore {
    static final DisabledAssetStore INSTANCE = new DisabledAssetStore();

    private DisabledAssetStore() {
    }

    @Override
    public boolean exists(String path) {
        return false;
    }

    @Override
    public ByteArray bytes(String path) {
        throw new IllegalStateException("Asset storage is not available");
    }

    @Override
    public String text(String path) {
        throw new IllegalStateException("Asset storage is not available");
    }

    @Override
    public List<String> list(String directory) {
        return List.of();
    }
}
