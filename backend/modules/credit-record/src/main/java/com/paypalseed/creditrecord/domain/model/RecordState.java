package com.paypalseed.creditrecord.domain.model;

import com.paypalseed.engines.rules.record.RecordFacts;
import com.paypalseed.engines.rules.record.RecordLevel;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The current state of one member's record: the fold of all their facts so far.
 *
 * <p>{@code lifetime} is the whole verified history, shown to the member and summarised to a partner. {@code current}
 * counts from the last default (or from the start) and is what the level is judged on. {@code openLate} and
 * {@code settled} hold {@code loanId:seq} keys so a payment that arrives before the miss it settles cannot leave a
 * miss open forever.
 */
public record RecordState(UUID memberId, RecordLevel level, LocalDate since, LocalDate levelSince, RecordFacts lifetime,
                          RecordFacts current, int loansDisbursed, int hardshipPlans, int defaults,
                          LocalDate lastSavingMonth, LocalDate savingAccountedThrough, int missedStreak,
                          Set<String> openLate, Set<String> settled, long factCount) {

    public RecordState {
        openLate = Set.copyOf(openLate);
        settled = Set.copyOf(settled);
    }

    public static RecordState initial(UUID memberId, LocalDate firstFactOn) {
        return new RecordState(memberId, RecordLevel.NEW, firstFactOn, firstFactOn, RecordFacts.EMPTY, RecordFacts.EMPTY, 0, 0,
                0, null, null, 0, Set.of(), Set.of(), 0);
    }
}
