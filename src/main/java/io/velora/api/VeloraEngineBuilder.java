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
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        return this;
    }

    public VeloraHost host() { return host; }
    public VeloraLimits limits() { return limits; }

    public VeloraEngine build() {
        if (host == null) throw new VeloraException("VeloraHost is required");
        if (host.mainThread() == null) throw new VeloraException("VeloraHost.mainThread() is required");
        if (host.workers() == null) throw new VeloraException("VeloraHost.workers() is required");
        if (host.clock() == null) throw new VeloraException("VeloraHost.clock() is required");
        if (host.logger() == null) throw new VeloraException("VeloraHost.logger() is required");
        return io.velora.internal.runtime.DefaultVeloraEngine.create(this);
    }
}
