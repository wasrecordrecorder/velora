package io.velora.api.task;

import java.util.ArrayList;
import java.util.List;

/**
 * Source side of a {@link CancellationToken}. Created per fiber / task tree and
 * linked to a parent for structured cancellation.
 */
public final class CancellationSource implements CancellationToken {

    private volatile boolean cancelled;
    private final List<Runnable> callbacks = new ArrayList<>();
    private final CancellationSource parent;
    private final Runnable parentUnsubscribe;

    private CancellationSource(CancellationSource parent) {
        this.parent = parent;
        if (parent != null) {
            this.parentUnsubscribe = this::doCancel;
            parent.onCancel(this.parentUnsubscribe);
        } else {
            this.parentUnsubscribe = null;
        }
    }

    /** Create a root cancellation source. */
    public static CancellationSource create() {
        return new CancellationSource(null);
    }

    /** Create a child linked to {@code parent}; cancelling the parent cancels the child. */
    public static CancellationSource childOf(CancellationSource parent) {
        return new CancellationSource(parent);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public synchronized void onCancel(Runnable callback) {
        if (cancelled) {
            callback.run();
            return;
        }
        callbacks.add(callback);
    }

    /** Request cancellation. Idempotent. */
    public synchronized boolean cancel() {
        if (cancelled) {
            return false;
        }
        doCancel();
        return true;
    }

    private void doCancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toRun = new ArrayList<>(callbacks);
            callbacks.clear();
        }
        for (Runnable r : toRun) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // cancellation callbacks must not propagate
            }
        }
    }
}
