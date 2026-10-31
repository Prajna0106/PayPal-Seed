package com.paypalseed.platform.testing;

import com.paypalseed.platform.kernel.BusinessClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** A business clock tests can set and advance. {@link #now()} is noon on the business date. */
public final class FakeBusinessClock implements BusinessClock {

    private volatile LocalDate today;

    public FakeBusinessClock(LocalDate today) {
        this.today = today;
    }

    @Override
    public Instant now() {
        return today.atTime(LocalTime.NOON).atZone(PROGRAM_ZONE).toInstant();
    }

    @Override
    public LocalDate today() {
        return today;
    }

    /** PayPal stamps real time, so money from PayPal counts on the simulated business date, as in the demo. */
    @Override
    public LocalDate businessDateFor(Instant paypalTime) {
        return today;
    }

    public void set(LocalDate date) {
        this.today = date;
    }
}
