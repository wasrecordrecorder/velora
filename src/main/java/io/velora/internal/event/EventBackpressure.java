package io.velora.internal.event;

import io.velora.api.event.EventOverflowPolicy;

import java.util.*;

public final class EventBackpressure {

    public enum Action { ACCEPT, DROP_OLDEST, DROP_NEWEST, COALESCE }

    public Action evaluate(EventOverflowPolicy policy, int currentSize, int queueLimit) {
        if (currentSize < queueLimit) return Action.ACCEPT;
        return switch (policy) {
            case DROP_OLDEST -> Action.DROP_OLDEST;
            case DROP_NEWEST -> Action.DROP_NEWEST;
            case KEEP_LATEST -> Action.DROP_OLDEST;
            case COALESCE -> Action.COALESCE;
            case FAIL_SCRIPT -> Action.DROP_NEWEST;
        };
    }
}
