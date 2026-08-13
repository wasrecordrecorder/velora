package io.velora.internal.security;

public final class ResourceCounter {
    private long memoryUsed;
    private int fibers;
    private int tasks;
    private int eventQueueSize;

    public void reserveMemory(long bytes) { if (bytes > 0) memoryUsed += bytes; }
    public void releaseMemory(long bytes) { if (bytes > 0) memoryUsed = Math.max(0, memoryUsed - bytes); }
    public long memoryUsed() { return memoryUsed; }

    public void reserveFiber() { fibers++; }
    public void releaseFiber() { fibers = Math.max(0, fibers - 1); }
    public int fibers() { return fibers; }

    public void reserveTask() { tasks++; }
    public void releaseTask() { tasks = Math.max(0, tasks - 1); }
    public int tasks() { return tasks; }

    public void reserveEvent() { eventQueueSize++; }
    public void releaseEvent() { eventQueueSize = Math.max(0, eventQueueSize - 1); }
    public int eventQueueSize() { return eventQueueSize; }

    public void reset() { memoryUsed = 0; fibers = 0; tasks = 0; eventQueueSize = 0; }
}
