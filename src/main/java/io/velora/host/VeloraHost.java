package io.velora.host;

public interface VeloraHost {
    String id();

    String version();

    MainThreadExecutor mainThread();

    WorkerExecutor workers();

    VeloraClock clock();

    VeloraLogger logger();

    VeloraFileSystem fileSystem();
}
