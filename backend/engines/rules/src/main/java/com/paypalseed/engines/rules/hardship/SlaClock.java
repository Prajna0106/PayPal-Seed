package com.paypalseed.engines.rules.hardship;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Decision SLA in business hours (03 §10): Monday to Friday in the program zone, no holiday calendar in v1.
 * {@code PENDING_APPROVAL} never expires on its own; these instants only drive escalation.
 */
public final class SlaClock {

    private SlaClock() {
    }

    public static Instant due(Instant start, boolean priority, HardshipPolicy policy) {
        return at(start, priority, 100, policy);
    }

    /** The instant {@code percent} of the budget has been used (50 and 80 drive the escalation timers). */
    public static Instant at(Instant start, boolean priority, int percent, HardshipPolicy policy) {
        Objects.requireNonNull(start, "start");
        HardshipPolicy.require(percent >= 1 && percent <= 100, "percent must be 1..100");
        HardshipPolicy.Timing t = policy.timing();
        long budget = (priority ? t.slaPriorityHours() : t.slaBusinessHours()) * 3_600L * percent / 100;
        ZonedDateTime now = start.atZone(t.zone());
        while (budget > 0) {
            now = snap(now, t);
            ZonedDateTime endOfDay = now.with(LocalTime.of(t.businessDayEndHour() % 24, 0));
            if (t.businessDayEndHour() == 24) {
                endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(t.zone());
            }
            long room = Math.min(budget, Duration.between(now, endOfDay).toSeconds());
            now = now.plusSeconds(room);
            budget -= room;
        }
        return now.toInstant();
    }

    /** Rolls forward to the next open business moment; a moment inside business hours is unchanged. */
    private static ZonedDateTime snap(ZonedDateTime when, HardshipPolicy.Timing t) {
        ZonedDateTime open = when.with(LocalTime.of(t.businessDayStartHour(), 0));
        ZonedDateTime candidate = when;
        if (candidate.isBefore(open)) {
            candidate = open;
        } else if (candidate.getHour() >= t.businessDayEndHour()) {
            candidate = open.plusDays(1);
        }
        while (isWeekend(candidate.getDayOfWeek())) {
            candidate = candidate.plusDays(1).with(LocalTime.of(t.businessDayStartHour(), 0));
        }
        return candidate;
    }

    private static boolean isWeekend(DayOfWeek day) {
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
