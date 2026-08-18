package io.velora.api.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Source side of a {@link CancellationToken}. Created per fiber / task tree and
 * linked to a parent for structured cancellation.
 */
public final class CancellationSource implements CancellationToken {

    private volatile boolean cancelled;
    private final List<Runnable> callbacks = new ArrayList<>();
    private final CancellationSource parent;
    private final Runnable parentCallback;

    private CancellationSource(CancellationSource parent) {
        this.parent = parent;
        if (parent != null) {
            this.parentCallback = this::doCancel;
            parent.onCancel(parentCallback);
        } else {
            this.parentCallback = null;
        }
    }

    /** Create a root cancellation source. */
    public static CancellationSource create() {
        return new CancellationSource(null);
    }

    /** Create a child linked to {@code parent}; cancelling the parent cancels the child. */
    public static CancellationSource childOf(CancellationSource parent) {
        return new CancellationSource(Objects.requireNonNull(parent));
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void onCancel(Runnable callback) {
        Objects.requireNonNull(callback);
        synchronized (this) {
            if (!cancelled) {
                callbacks.add(callback);
                return;
            }
        }
        callback.run();
    }

    /** Request cancellation. Idempotent. */
    public boolean cancel() {
        return doCancel();
    }

    private boolean doCancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) return false;
            cancelled = true;
            toRun = new ArrayList<>(callbacks);
            callbacks.clear();
        }
        if (parent != null) parent.removeCallback(parentCallback);
        for (Runnable callback : toRun) {
            try {
                callback.run();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private synchronized void removeCallback(Runnable callback) {
        callbacks.remove(callback);
    }
}
