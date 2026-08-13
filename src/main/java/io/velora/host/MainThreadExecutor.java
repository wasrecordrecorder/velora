package io.velora.host;

public interface MainThreadExecutor {
    boolean isMainThread();

    void execute(Runnable action);
}
