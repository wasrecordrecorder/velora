package io.velora.internal.security;

public final class ResourceCounter {
    private long memoryUsed;
    private int fibers;
    private int tasks;
    private int eventQueueSize;

    public void reserveMemory(long bytes) { memoryUsed += bytes; }
    public void releaseMemory(long bytes) { memoryUsed -= bytes; }
    public long memoryUsed() { return memoryUsed; }

    public void reserveFiber() { fibers++; }
    public void releaseFiber() { fibers--; }
    public int fibers() { return fibers; }

    public void reserveTask() { tasks++; }
    public void releaseTask() { tasks--; }
    public int tasks() { return tasks; }

    public void reserveEvent() { eventQueueSize++; }
    public void releaseEvent() { eventQueueSize--; }
    public int eventQueueSize() { return eventQueueSize; }

    public void reset() { memoryUsed = 0; fibers = 0; tasks = 0; eventQueueSize = 0; }
}
