package com.paypalseed.platform.persistence;

import java.time.Duration;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs statements slower than a threshold. It logs the SQL with its bind markers and never the bound
 * values, which can hold personal data.
 */
final class SlowQueryListener implements ExecuteListener {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryListener.class);
    private static final String STARTED_AT = "seed.query.started-at";

    private final long thresholdNanos;

    SlowQueryListener(Duration threshold) {
        this.thresholdNanos = threshold.toNanos();
    }

    @Override
    public void executeStart(ExecuteContext ctx) {
        ctx.data(STARTED_AT, System.nanoTime());
    }

    @Override
    public void executeEnd(ExecuteContext ctx) {
        if (ctx.data(STARTED_AT) instanceof Long startedAt) {
            long elapsed = System.nanoTime() - startedAt;
            if (elapsed >= thresholdNanos) {
                log.warn("Slow query ({} ms): {}", elapsed / 1_000_000, ctx.sql());
            }
        }
    }
}
