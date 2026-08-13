package io.velora.api;

import io.velora.host.VeloraHost;

/**
 * Builder for {@link VeloraEngine}.
 */
public final class VeloraEngineBuilder {

    private VeloraHost host;
    private VeloraLimits limits = VeloraLimits.defaults();

    VeloraEngineBuilder() {}

    public VeloraEngineBuilder host(VeloraHost host) {
        this.host = host;
        return this;
    }

    public VeloraEngineBuilder limits(VeloraLimits limits) {
        this.limits = limits;
        return this;
    }

    public VeloraHost host() { return host; }
    public VeloraLimits limits() { return limits; }

    public VeloraEngine build() {
        if (host == null) {
            throw new VeloraException("VeloraHost is required");
        }
        return io.velora.internal.runtime.DefaultVeloraEngine.create(this);
    }
}
