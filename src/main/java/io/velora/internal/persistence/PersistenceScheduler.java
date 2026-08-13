package io.velora.internal.persistence;

import java.util.*;
import java.util.concurrent.*;

public final class PersistenceScheduler {
    private final ScheduledExecutorService executor;
    private final Queue<Runnable> pendingFlushes = new ConcurrentLinkedQueue<>();

    public PersistenceScheduler(int threads) {
        this.executor = Executors.newScheduledThreadPool(Math.max(1, threads));
    }

    public void scheduleFlush(Runnable task) {
        pendingFlushes.add(task);
    }

    public void flushNow() {
        Runnable task;
        while ((task = pendingFlushes.poll()) != null) {
            try { task.run(); }
            catch (Throwable ignored) {}
        }
    }

    public void schedulePeriodic(Runnable task, long intervalMillis) {
        executor.scheduleAtFixedRate(task, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        flushNow();
        executor.shutdown();
    }
}
