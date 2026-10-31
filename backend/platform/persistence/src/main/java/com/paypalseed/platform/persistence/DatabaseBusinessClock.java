package com.paypalseed.platform.persistence;

import com.paypalseed.platform.kernel.BusinessClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo clock shared by every process role: the business date lives in {@code platform.business_date}
 * (the Fineract {@code m_business_date} idea), so the api, ingress and worker roles always agree.
 * Enabled with {@code seed.clock.mode=database}; production uses the system clock.
 */
public class DatabaseBusinessClock implements BusinessClock {

    private final JdbcClient jdbc;
    private final Clock wallClock;

    public DatabaseBusinessClock(JdbcClient jdbc, Clock wallClock) {
        this.jdbc = jdbc;
        this.wallClock = wallClock;
    }

    @Override
    public LocalDate today() {
        return jdbc.sql("SELECT business_date FROM platform.business_date WHERE id = 1")
                .query(LocalDate.class)
                .optional()
                .orElseGet(() -> LocalDate.now(wallClock.withZone(PROGRAM_ZONE)));
    }

    /** Noon on the business date, so instants derived from the demo date fall on that date. */
    @Override
    public Instant now() {
        return today().atTime(LocalTime.NOON).atZone(PROGRAM_ZONE).toInstant();
    }

    /** PayPal stamps real time; in the demo, money counts on the current (simulated) business date. */
    @Override
    public LocalDate businessDateFor(Instant paypalTime) {
        return today();
    }

    /** Moves the business date forward; returns the new date. Never moves backwards. */
    @Transactional
    public LocalDate advance(int days) {
        if (days < 1) {
            throw new IllegalArgumentException("the business date only moves forward");
        }
        LocalDate next = today().plusDays(days);
        jdbc.sql("""
                INSERT INTO platform.business_date (id, business_date) VALUES (1, :d)
                ON CONFLICT (id) DO UPDATE SET business_date = EXCLUDED.business_date, updated_at = now()
                """).param("d", next).update();
        return next;
    }
}
