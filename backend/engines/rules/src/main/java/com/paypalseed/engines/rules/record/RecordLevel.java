package com.paypalseed.engines.rules.record;

import com.paypalseed.engines.rules.LoanTier;
import java.util.Optional;

/**
 * The five levels of a member's credit record (F6). A level is earned by a verified history of saving and
 * repaying; it is never a score. {@link #loanLimitCents()} is the loan ceiling the level unlocks, in cents, in the
 * same units as {@link LoanTier#maxLoanCents()} so the eligibility engine can take the larger of the two.
 */
public enum RecordLevel {

    NEW(0, 0L),
    BUILDING(1, 150_000L),
    STEADY(2, 300_000L),
    TRUSTED(3, 500_000L),
    EXEMPLARY(4, 750_000L);

    private final int rank;
    private final long loanLimitCents;

    RecordLevel(int rank, long loanLimitCents) {
        this.rank = rank;
        this.loanLimitCents = loanLimitCents;
    }

    public int rank() {
        return rank;
    }

    /** Non-negative; zero for {@link #NEW}, which unlocks nothing beyond what the saving streak already gives. */
    public long loanLimitCents() {
        return loanLimitCents;
    }

    public boolean atLeast(RecordLevel other) {
        return rank >= other.rank;
    }

    public Optional<RecordLevel> next() {
        RecordLevel[] all = values();
        return rank + 1 < all.length ? Optional.of(all[rank + 1]) : Optional.empty();
    }

    /** The rung of the existing microloan ladder this level corresponds to. */
    public LoanTier ladderTier() {
        return switch (this) {
            case NEW -> LoanTier.UNQUALIFIED;
            case BUILDING -> LoanTier.LEVEL_1;
            case STEADY -> LoanTier.LEVEL_2;
            case TRUSTED, EXEMPLARY -> LoanTier.LEVEL_3;
        };
    }

    public static RecordLevel ofRank(int rank) {
        RecordLevel[] all = values();
        if (rank < 0 || rank >= all.length) {
            throw new IllegalArgumentException("No record level with rank " + rank);
        }
        return all[rank];
    }
}
