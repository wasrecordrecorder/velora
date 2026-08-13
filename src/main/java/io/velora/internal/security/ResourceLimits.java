package io.velora.internal.security;

import io.velora.api.VeloraLimits;

public final class ResourceLimits {
    private final VeloraLimits limits;

    public ResourceLimits(VeloraLimits limits) {
        this.limits = limits;
    }

    public boolean memoryExceeded(ResourceCounter counter) {
        return counter.memoryUsed() > limits.memoryPerScript();
    }

    public boolean fibersExceeded(ResourceCounter counter) {
        return counter.fibers() > limits.maxFibersPerScript();
    }

    public boolean tasksExceeded(ResourceCounter counter) {
        return counter.tasks() > limits.maxTasksPerScript();
    }

    public boolean eventQueueExceeded(ResourceCounter counter) {
        return counter.eventQueueSize() > limits.maxEventQueuePerScript();
    }

    public boolean stringLengthExceeded(int length) {
        return length > limits.maxStringLength();
    }

    public boolean collectionElementsExceeded(int size) {
        return size > limits.maxCollectionElements();
    }

    public boolean callDepthExceeded(int depth) {
        return depth > limits.maxCallDepth();
    }

    public VeloraLimits limits() {
        return limits;
    }
}
