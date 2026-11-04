package com.paypalseed.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Counts requests per key in fixed one-minute windows, in memory. The map is bounded: when it is full of live windows a
 * new key is refused instead of stored, so a flood of distinct addresses cannot exhaust the heap.
 */
final class FixedWindowRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(1);

    /** {@code retryAfterSeconds} is 0 when the request is allowed. */
    record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private static final class Window {
        final long endMillis;
        int count;

        Window(long endMillis) {
            this.endMillis = endMillis;
        }
    }

    private final Clock clock;
    private final int limit;
    private final int maxKeys;
    private final Map<String, Window> windows = new HashMap<>();

    FixedWindowRateLimiter(Clock clock, int limit, int maxKeys) {
        this.clock = clock;
        this.limit = limit;
        this.maxKeys = maxKeys;
    }

    synchronized Decision tryAcquire(String key) {
        long now = clock.millis();
        Window w = windows.get(key);
        if (w != null && w.endMillis <= now) {
            windows.remove(key);
            w = null;
        }
        if (w == null) {
            if (windows.size() >= maxKeys) {
                windows.values().removeIf(x -> x.endMillis <= now);
                if (windows.size() >= maxKeys) {
                    return new Decision(false, WINDOW.toSeconds());
                }
            }
            w = new Window(now + WINDOW.toMillis());
            windows.put(key, w);
        }
        if (w.count >= limit) {
            return new Decision(false, Math.max(1, (w.endMillis - now + 999) / 1000));
        }
        w.count++;
        return new Decision(true, 0);
    }

    synchronized int trackedKeys() {
        return windows.size();
    }
}
