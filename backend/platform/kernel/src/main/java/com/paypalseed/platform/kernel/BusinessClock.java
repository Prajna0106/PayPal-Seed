package com.paypalseed.platform.kernel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The program's notion of "today". Loan logic runs on a business date in the program time zone,
 * not on wall-clock instants, so a demo clock can move months in seconds and daylight saving time
 * never shifts a due date (ADR 0007).
 */
public interface BusinessClock {

    ZoneId PROGRAM_ZONE = ZoneId.of("America/Los_Angeles");

    Instant now();

    LocalDate today();

    /** The business date on which a PayPal timestamp (UTC) took effect. */
    static LocalDate businessDateOf(Instant instant) {
        return instant.atZone(PROGRAM_ZONE).toLocalDate();
    }

    /**
     * The business date a money event from PayPal counts on. Normally the date of its timestamp; a
     * demo clock overrides this, because PayPal stamps real time while the demo runs months ahead.
     */
    default LocalDate businessDateFor(Instant paypalTime) {
        return businessDateOf(paypalTime);
    }
}
