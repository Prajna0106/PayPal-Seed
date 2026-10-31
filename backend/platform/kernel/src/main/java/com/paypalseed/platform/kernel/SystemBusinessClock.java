package com.paypalseed.platform.kernel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/** Production clock: the business date is the calendar date in the program time zone. */
public final class SystemBusinessClock implements BusinessClock {

    private final Clock clock;

    public SystemBusinessClock() {
        this(Clock.system(PROGRAM_ZONE));
    }

    public SystemBusinessClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public LocalDate today() {
        return LocalDate.now(clock.withZone(PROGRAM_ZONE));
    }
}
