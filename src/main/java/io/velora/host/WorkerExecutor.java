package io.velora.host;

public interface WorkerExecutor {
    void execute(Runnable action);

    void shutdown();
}
