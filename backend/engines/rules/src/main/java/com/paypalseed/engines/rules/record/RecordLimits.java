package com.paypalseed.engines.rules.record;

import java.util.Objects;

/** How a record level feeds the loan ceiling. Integer cents throughout; no input overflows or goes negative. */
public final class RecordLimits {

    private RecordLimits() {
    }

    /**
     * The ceiling after the record is taken into account: the larger of what the ladder already allows and what
     * the level unlocks. The record only ever raises a limit; a NEW record leaves it as it is.
     */
    public static long limitCents(RecordLevel level, long baseLimitCents) {
        Objects.requireNonNull(level, "level");
        return Math.max(Math.max(0L, baseLimitCents), level.loanLimitCents());
    }
}
