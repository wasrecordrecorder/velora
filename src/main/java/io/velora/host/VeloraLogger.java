package io.velora.host;

public interface VeloraLogger {
    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message, Throwable throwable);
}
