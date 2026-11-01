package com.paypalseed.creditrecord.domain.model;

import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.engines.rules.record.RecordFacts;
import com.paypalseed.engines.rules.record.RecordLevel;
import com.paypalseed.engines.rules.record.RecordLevels;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The projection: folds one fact into a member's state. Pure, so replaying a member's facts in order always
 * rebuilds the same state.
 *
 * <p>Standing rules in one place: a hardship plan only raises a counter that nobody reads for the level; a payment
 * inside grace is on time; a level only ever rises, except at a default (see {@link RecordLevels}).
 */
public final class RecordProjector {

    /** After this many months in a row without a deposit the member has stopped saving; more months are not held against them. */
    static final int MAX_CONSECUTIVE_MISSED_MONTHS = 3;

    public enum Cause {
        PROMOTION,
        DEFAULT
    }

    /** The new state, and the level move it caused (or none). */
    public record Applied(RecordState state, RecordLevel from, RecordLevel to, Cause cause) {
        public boolean levelChanged() {
            return from != to;
        }
    }

    private RecordProjector() {
    }

    public static Applied apply(RecordState before, RecordFact fact) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(fact, "fact");
        Draft d = new Draft(before);
        if (fact.occurredOn().isBefore(d.since)) {
            d.since = fact.occurredOn();
        }
        boolean defaulted = false;
        switch (fact.kind()) {
            case SAVINGS_DEPOSIT -> deposit(d, YearMonth.from(fact.occurredOn()).atDay(1));
            case SAVING_MONTHS_CLOSED -> closeMonths(d, YearMonth.from(fact.occurredOn()).minusMonths(1).atDay(1));
            case LOAN_DISBURSED -> d.loansDisbursed++;
            case INSTALMENT_PAID -> paid(d, key(fact), fact.daysLate());
            case INSTALMENT_MISSED -> missed(d, key(fact), fact.loanId());
            case LOAN_REPAID -> repaid(d, fact);
            case LOAN_DEFAULTED -> {
                defaulted = true;
                d.defaults++;
                d.openLate.removeIf(k -> k.startsWith(fact.loanId() + ":"));
                d.settled.add(defaultMarker(fact.loanId()));
                d.current = RecordFacts.EMPTY;
            }
            case HARDSHIP_PLAN_APPLIED -> d.hardshipPlans++;
        }
        d.factCount++;

        RecordLevel from = before.level();
        RecordLevel to = defaulted ? RecordLevels.afterDefault() : RecordLevels.next(from, d.current.withOpenLate(d.openLate.size()));
        if (to != from) {
            d.levelSince = fact.occurredOn();
        }
        d.level = to;
        Cause cause = defaulted ? Cause.DEFAULT : Cause.PROMOTION;
        return new Applied(d.toState(), from, to, cause);
    }

    private static void deposit(Draft d, LocalDate month) {
        if (d.accountedThrough != null && !month.isAfter(d.accountedThrough)) {
            return; // another deposit in a month already counted
        }
        if (d.accountedThrough != null) {
            countGap(d, d.accountedThrough, month);
        }
        d.lifetime = d.lifetime.plusSavingMonthOnTime();
        d.current = d.current.plusSavingMonthOnTime();
        d.lastSavingMonth = month;
        d.accountedThrough = month;
        d.missedStreak = 0;
    }

    private static void closeMonths(Draft d, LocalDate through) {
        if (d.accountedThrough == null || !through.isAfter(d.accountedThrough)) {
            return; // not saving yet, or already counted
        }
        countGap(d, d.accountedThrough, through.plusMonths(1));
        d.accountedThrough = through;
    }

    /** Counts the months strictly between {@code accounted} and {@code until} as missed, up to the streak cap. */
    private static void countGap(Draft d, LocalDate accounted, LocalDate until) {
        int gap = (int) ChronoUnit.MONTHS.between(accounted, until) - 1;
        if (gap <= 0) {
            return;
        }
        int counted = Math.max(0, Math.min(gap, MAX_CONSECUTIVE_MISSED_MONTHS - d.missedStreak));
        if (counted > 0) {
            d.lifetime = d.lifetime.plusSavingMonthsMissed(counted);
            d.current = d.current.plusSavingMonthsMissed(counted);
        }
        d.missedStreak = (int) Math.min(Integer.MAX_VALUE, (long) d.missedStreak + gap);
    }

    private static void paid(Draft d, String key, int daysLate) {
        if (!d.settled.add(key)) {
            return;
        }
        d.openLate.remove(key);
        UnaryOperator<RecordFacts> add = RecordLevels.paidOnTime(daysLate) ? RecordFacts::plusInstalmentOnTime
                : RecordFacts::plusInstalmentLate;
        d.lifetime = add.apply(d.lifetime);
        d.current = add.apply(d.current);
    }

    /** A miss that arrives after its loan's default (the events are not ordered) is part of the default, not a new debt. */
    private static void missed(Draft d, String key, UUID loanId) {
        if (!d.settled.contains(key) && !d.settled.contains(defaultMarker(loanId))) {
            d.openLate.add(key);
        }
    }

    private static String defaultMarker(UUID loanId) {
        return loanId + ":default";
    }

    private static void repaid(Draft d, RecordFact fact) {
        boolean onPlan = fact.missedCount() == 0;
        d.lifetime = d.lifetime.plusLoanRepaid(onPlan);
        d.current = d.current.plusLoanRepaid(onPlan);
        d.openLate.removeIf(k -> k.startsWith(fact.loanId() + ":"));
    }

    private static String key(RecordFact fact) {
        return fact.loanId() + ":" + fact.seq();
    }

    private static final class Draft {
        final UUID memberId;
        RecordLevel level;
        LocalDate since;
        LocalDate levelSince;
        RecordFacts lifetime;
        RecordFacts current;
        int loansDisbursed;
        int hardshipPlans;
        int defaults;
        LocalDate lastSavingMonth;
        LocalDate accountedThrough;
        int missedStreak;
        final Set<String> openLate;
        final Set<String> settled;
        long factCount;

        Draft(RecordState s) {
            memberId = s.memberId();
            level = s.level();
            since = s.since();
            levelSince = s.levelSince();
            lifetime = s.lifetime();
            current = s.current();
            loansDisbursed = s.loansDisbursed();
            hardshipPlans = s.hardshipPlans();
            defaults = s.defaults();
            lastSavingMonth = s.lastSavingMonth();
            accountedThrough = s.savingAccountedThrough();
            missedStreak = s.missedStreak();
            openLate = new HashSet<>(s.openLate());
            settled = new HashSet<>(s.settled());
            factCount = s.factCount();
        }

        RecordState toState() {
            int open = openLate.size();
            return new RecordState(memberId, level, since, levelSince, lifetime.withOpenLate(open), current.withOpenLate(open),
                    loansDisbursed, hardshipPlans, defaults, lastSavingMonth, accountedThrough, missedStreak, openLate,
                    settled, factCount);
        }
    }
}
