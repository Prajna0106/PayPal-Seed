package com.paypalseed.engagement.domain.model;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** No texts between 20:00 and 08:00 in the member's own time zone: messages are deferred, never dropped. */
public final class QuietHours {

    public static final LocalTime START = LocalTime.of(20, 0);
    public static final LocalTime END = LocalTime.of(8, 0);

    private QuietHours() {
    }

    /** The earliest instant at or after {@code at} that is outside quiet hours in {@code zone}. */
    public static Instant nextAllowed(Instant at, ZoneId zone) {
        ZonedDateTime local = at.atZone(zone);
        LocalTime t = local.toLocalTime();
        if (!t.isBefore(START)) {
            return local.toLocalDate().plusDays(1).atTime(END).atZone(zone).toInstant();
        }
        if (t.isBefore(END)) {
            return local.toLocalDate().atTime(END).atZone(zone).toInstant();
        }
        return at;
    }
}
