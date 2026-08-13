package io.velora.host;

public interface VeloraClock {
    long nanoTime();

    long currentTimeMillis();
}
