package io.velora.internal.language;

import java.util.concurrent.atomic.AtomicLong;

public final class EditorRevisionController {
    private final AtomicLong revisionCounter = new AtomicLong(0);

    public long next() { return revisionCounter.incrementAndGet(); }
    public long current() { return revisionCounter.get(); }
    public void reset() { revisionCounter.set(0); }
}
