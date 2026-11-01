package com.paypalseed.deskquery.domain.model;

import java.time.Duration;
import java.time.Instant;

/** Where an open item stands against its service-level deadline. */
public enum SlaBucket {
    OVERDUE,
    DUE_SOON,
    ON_TRACK,
    /** The item has no deadline (for example, a request whose opening event has not arrived yet). */
    NO_SLA;

    /** A deadline exactly now is overdue; a deadline exactly {@code window} away is due soon. */
    public static SlaBucket of(Instant slaDueAt, Instant now, Duration dueSoonWindow) {
        if (slaDueAt == null) {
            return NO_SLA;
        }
        if (!slaDueAt.isAfter(now)) {
            return OVERDUE;
        }
        return slaDueAt.isAfter(now.plus(dueSoonWindow)) ? ON_TRACK : DUE_SOON;
    }
}
