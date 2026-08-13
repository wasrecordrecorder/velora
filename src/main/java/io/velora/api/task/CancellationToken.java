package io.velora.api.task;

/**
 * Token observable by host adapters to detect cancellation of a fiber or task tree.
 *
 * <p>Cancellation is cooperative: the runtime checks the token at suspend points
 * and forced-yield boundaries. Host adapters register an idempotent callback via
 * {@link #onCancel(Runnable)} to release external resources.
 */
public interface CancellationToken {

    /** Whether cancellation has been requested. */
    boolean isCancelled();

    /**
     * Register a callback invoked when cancellation is requested. The callback
     * must be idempotent. If already cancelled, the callback is invoked immediately.
     */
    void onCancel(Runnable callback);

    /**
     * Throw {@link CancellationException} if cancellation was requested.
     */
    default void checkCancelled() {
        if (isCancelled()) {
            throw new CancellationException();
        }
    }

    /** Exception used to unwind a cancelled fiber. It is not a runtime failure. */
    final class CancellationException extends RuntimeException {
        public CancellationException() {
            super("Fiber cancelled", null, false, false);
        }
    }
}
