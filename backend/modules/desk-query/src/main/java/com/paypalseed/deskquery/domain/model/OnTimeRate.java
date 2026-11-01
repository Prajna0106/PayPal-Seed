package com.paypalseed.deskquery.domain.model;

/**
 * Share of instalments that fell due and were not recorded as missed. Missed instalments are append-only facts, so a late
 * payment that was later cured still counts as missed: the rate is the honest one, not the flattering one.
 */
public record OnTimeRate(long instalmentsDue, long instalmentsMissed, Integer rateBasisPoints) {

    public static OnTimeRate of(long instalmentsDue, long instalmentsMissed) {
        long due = Math.max(0, instalmentsDue);
        long missed = Math.min(Math.max(0, instalmentsMissed), due);
        Integer rate = due == 0 ? null : (int) (((due - missed) * 10_000 + due / 2) / due);
        return new OnTimeRate(due, missed, rate);
    }
}
