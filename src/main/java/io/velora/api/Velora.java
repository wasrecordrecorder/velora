package io.velora.api;

/**
 * Entry point for creating Velora engines.
 */
public final class Velora {

    private Velora() {}

    /**
     * Create a new engine builder. A host is required.
     */
    public static VeloraEngineBuilder builder() {
        return new VeloraEngineBuilder();
    }

    /**
     * The engine version.
     */
    public static String version() {
        return "1.0.0";
    }
}
