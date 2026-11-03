package com.paypalseed.creditrecord.domain.model;

import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.domain.model.SettledKeys.LoanClass;
import com.paypalseed.engines.rules.record.RecordFacts;
import com.paypalseed.engines.rules.record.RecordLevel;
import com.paypalseed.engines.rules.record.RecordLevels;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
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
        DEFAULT,
        /**
         * A loan counted as the member's was found to be the safety fund's after the level had risen on it, because the
         * facts arrived out of order (ADR 0025), or a loan was voided and the record was rebuilt without it (ADR 0025
         * follow-up). The level returns to what the member's own record supports.
         */
        CORRECTION
    }

    /** The new state, and the level move it caused (or none). */
    public record Applied(RecordState state, RecordLevel from, RecordLevel to, Cause cause) {
        public boolean levelChanged() {
            return from != to;
        }
    }

    private RecordProjector() {
    }

    /** Facts that belong to one loan: once the loan is void none of them counts. */
    private static final Set<RecordFact.Kind> LOAN_SCOPED = EnumSet.of(RecordFact.Kind.LOAN_DISBURSED,
            RecordFact.Kind.INSTALMENT_PAID, RecordFact.Kind.INSTALMENT_MISSED, RecordFact.Kind.INSTALMENT_FUND_COVERED,
            RecordFact.Kind.LOAN_REPAID, RecordFact.Kind.LOAN_DEFAULTED, RecordFact.Kind.HARDSHIP_PLAN_APPLIED);

    private static boolean ofLoan(RecordFact fact) {
        return fact.loanId() != null && LOAN_SCOPED.contains(fact.kind());
    }

    /**
     * Rebuilds a member's state from all their facts, in the order they were stored, as if every voided loan had never
     * existed (ADR 0025 follow-up). The loan's facts are append-only and stay in the store; the fold skips them, so the
     * loan, its instalments, its on-plan or off-plan count, its default and any level it lifted all disappear in one
     * step, whatever order the events arrived in. The result is the {@code CORRECTION} of the level the member held
     * before ({@code levelBefore}); it can be lower (a promotion the loan caused) or higher (a default it caused).
     * The void marker is kept in the state, so a fact for that loan that arrives later is ignored.
     */
    public static Applied replay(UUID memberId, RecordLevel levelBefore, List<RecordFact> inOrder) {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(levelBefore, "levelBefore");
        Set<UUID> voided = new HashSet<>();
        for (RecordFact f : inOrder) {
            if (f.kind() == RecordFact.Kind.LOAN_VOIDED) {
                voided.add(f.loanId());
            }
        }
        RecordState state = null;
        long skipped = 0;
        for (RecordFact f : inOrder) {
            if (f.kind() == RecordFact.Kind.LOAN_VOIDED) {
                continue;
            }
            if (ofLoan(f) && voided.contains(f.loanId())) {
                skipped++;
                continue;
            }
            state = apply(state == null ? RecordState.initial(memberId, f.occurredOn()) : state, f).state();
        }
        // Markers last, so a record with nothing but a void does not start on the day the payout came back.
        for (RecordFact f : inOrder) {
            if (f.kind() == RecordFact.Kind.LOAN_VOIDED) {
                state = apply(state == null ? RecordState.initial(memberId, f.occurredOn()) : state, f).state();
            }
        }
        Objects.requireNonNull(state, "no facts to replay");
        RecordState total = new RecordState(state.memberId(), state.level(), state.since(), state.levelSince(),
                state.lifetime(), state.current(), state.loansDisbursed(), state.hardshipPlans(), state.defaults(),
                state.lastSavingMonth(), state.savingAccountedThrough(), state.missedStreak(), state.openLate(),
                state.settled(), state.factCount() + skipped);
        return new Applied(total, levelBefore, total.level(), Cause.CORRECTION);
    }

    public static Applied apply(RecordState before, RecordFact fact) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(fact, "fact");
        Draft d = new Draft(before);
        if (ofLoan(fact) && d.settled.contains(SettledKeys.voided(fact.loanId()))) {
            d.factCount++; // stored, never counted: the loan is void
            return new Applied(d.toState(), before.level(), before.level(), Cause.PROMOTION);
        }
        if (fact.occurredOn().isBefore(d.since)) {
            d.since = fact.occurredOn();
        }
        boolean defaulted = false;
        switch (fact.kind()) {
            case SAVINGS_DEPOSIT -> deposit(d, YearMonth.from(fact.occurredOn()).atDay(1));
            case SAVING_MONTHS_CLOSED -> closeMonths(d, YearMonth.from(fact.occurredOn()).minusMonths(1).atDay(1));
            case LOAN_DISBURSED -> d.loansDisbursed++;
            case INSTALMENT_PAID -> paid(d, fact.loanId(), key(fact), fact.daysLate());
            case INSTALMENT_MISSED -> missed(d, fact.loanId(), key(fact));
            case INSTALMENT_FUND_COVERED -> fundCovered(d, fact.loanId(), key(fact));
            case LOAN_REPAID -> repaid(d, fact);
            case LOAN_DEFAULTED -> {
                defaulted = true;
                d.defaults++;
                d.openLate.removeIf(k -> k.startsWith(fact.loanId() + ":"));
                d.settled.add(defaultMarker(fact.loanId()));
                d.settled.removeIf(SettledKeys::isLift);
                d.current = RecordFacts.EMPTY;
            }
            case HARDSHIP_PLAN_APPLIED -> d.hardshipPlans++;
            // Only a marker here; taking the loan's effects back is a rebuild (see replay), which RecordIngest runs.
            case LOAN_VOIDED -> d.settled.add(SettledKeys.voided(fact.loanId()));
        }
        d.factCount++;

        RecordLevel from = before.level();
        RecordLevel to = defaulted ? RecordLevels.afterDefault()
                : d.corrected != null ? d.corrected : RecordLevels.next(from, d.current.withOpenLate(d.openLate.size()));
        if (to != from) {
            d.levelSince = fact.occurredOn();
            if (fact.kind() == RecordFact.Kind.LOAN_REPAID && to.rank() > from.rank()) {
                // Remembered so the rise can be taken back if this loan turns out to be the fund's (see retract).
                d.settled.add(SettledKeys.lift(fact.loanId(), from.rank()));
            }
        }
        d.level = to;
        Cause cause = defaulted ? Cause.DEFAULT : d.corrected != null ? Cause.CORRECTION : Cause.PROMOTION;
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

    private static void paid(Draft d, UUID loanId, String key, int daysLate) {
        if (!d.settled.add(key)) {
            return;
        }
        d.openLate.remove(key);
        // What the fund paid is not the member's own repayment: neither on time nor late.
        if (!d.settled.contains(SettledKeys.fund(key))) {
            boolean onTime = RecordLevels.paidOnTime(daysLate);
            if (!onTime) {
                d.settled.add(SettledKeys.late(key));
            }
            UnaryOperator<RecordFacts> add = onTime ? RecordFacts::plusInstalmentOnTime : RecordFacts::plusInstalmentLate;
            d.lifetime = add.apply(d.lifetime);
            d.current = add.apply(d.current);
        }
        reclassify(d, loanId);
    }

    /** A miss that arrives after its loan's default (the events are not ordered) is part of the default, not a new debt. */
    private static void missed(Draft d, UUID loanId, String key) {
        if (d.settled.contains(defaultMarker(loanId))) {
            return;
        }
        d.settled.add(SettledKeys.missed(key));
        if (!d.settled.contains(key) && !d.settled.contains(SettledKeys.fund(key))) {
            d.openLate.add(key);
        }
        reclassify(d, loanId);
    }

    /**
     * The fund paid this whole instalment (ADR 0025). Nothing stays open against the member for it and it adds to neither
     * count; if the member's own payment was counted before this fact arrived, that count is taken back.
     */
    private static void fundCovered(Draft d, UUID loanId, String key) {
        if (!d.settled.add(SettledKeys.fund(key))) {
            return;
        }
        d.openLate.remove(key);
        if (d.settled.contains(key)) {
            boolean late = d.settled.remove(SettledKeys.late(key));
            d.lifetime = withoutInstalment(d.lifetime, late);
            d.current = withoutInstalment(d.current, late);
        }
        reclassify(d, loanId);
    }

    private static String defaultMarker(UUID loanId) {
        return loanId + ":default";
    }

    private static void repaid(Draft d, RecordFact fact) {
        d.settled.add(SettledKeys.repaid(fact.loanId(), fact.missedCount()));
        d.openLate.removeIf(k -> k.startsWith(fact.loanId() + ":"));
        reclassify(d, fact.loanId());
    }

    /**
     * Counts a repaid loan as the instalment facts known so far say, and corrects the count when a later fact changes
     * the answer, so the result does not depend on the order the events arrived in. A loan every instalment of which
     * the fund paid is the fund's, not the member's: it counts toward neither on plan nor off plan. Misses on
     * instalments the fund covered are not the member's either.
     */
    private static void reclassify(Draft d, UUID loanId) {
        Integer lendingMisses = d.settled.stream().filter(k -> SettledKeys.isRepaid(k, loanId)).findFirst()
                .map(SettledKeys::missedCountOf).orElse(null);
        if (lendingMisses == null) {
            return;
        }
        int fundCovered = 0;
        int fundMissed = 0;
        int memberPaid = 0;
        for (String k : d.settled) {
            if (SettledKeys.isFund(k, loanId)) {
                fundCovered++;
                if (d.settled.contains(SettledKeys.missed(SettledKeys.instalmentOfFund(k)))) {
                    fundMissed++;
                }
            } else if (SettledKeys.isInstalment(k, loanId) && !d.settled.contains(SettledKeys.fund(k))) {
                memberPaid++;
            }
        }
        LoanClass now = fundCovered > 0 && memberPaid == 0 ? LoanClass.FUND
                : lendingMisses - fundMissed <= 0 ? LoanClass.PLAN : LoanClass.OFF_PLAN;
        LoanClass before = null;
        for (LoanClass c : LoanClass.values()) {
            if (d.settled.contains(SettledKeys.classOf(loanId, c))) {
                before = c;
            }
        }
        if (now == before) {
            return;
        }
        if (before != null) {
            d.settled.remove(SettledKeys.classOf(loanId, before));
            d.lifetime = withoutLoan(d.lifetime, before);
            d.current = withoutLoan(d.current, before);
            if (before == LoanClass.PLAN) {
                retract(d, loanId);
            }
        }
        d.settled.add(SettledKeys.classOf(loanId, now));
        if (now != LoanClass.FUND) {
            boolean onPlan = now == LoanClass.PLAN;
            d.lifetime = d.lifetime.plusLoanRepaid(onPlan);
            d.current = d.current.plusLoanRepaid(onPlan);
        }
    }

    /**
     * The loan was first counted on plan and a promotion followed. Events are not ordered, so the fund's part can reach
     * the record after the loan's repayment did. The level goes back to the higher of what it was before that loan and
     * what the corrected counts support, so the fund's money never leaves the member higher than their own record does.
     */
    private static void retract(Draft d, UUID loanId) {
        String lift = d.settled.stream().filter(k -> SettledKeys.isLift(k, loanId)).findFirst().orElse(null);
        if (lift == null) {
            return;
        }
        d.settled.remove(lift);
        RecordLevel floor = RecordLevel.ofRank(SettledKeys.liftedFromRank(lift));
        RecordLevel supported = RecordLevels.evaluate(d.current.withOpenLate(d.openLate.size()));
        RecordLevel corrected = supported.atLeast(floor) ? supported : floor;
        if (corrected.rank() < d.level.rank()) {
            d.corrected = corrected;
        }
    }

    // RecordFacts only counts up; taking a count back is rare and local to this fold, and never goes below zero.
    private static RecordFacts withoutInstalment(RecordFacts f, boolean late) {
        return new RecordFacts(f.onTimeSavingMonths(), f.missedSavingMonths(), f.loansRepaidOnPlan(), f.loansRepaidOffPlan(),
                late ? f.instalmentsOnTime() : Math.max(0, f.instalmentsOnTime() - 1),
                late ? Math.max(0, f.instalmentsLate() - 1) : f.instalmentsLate(), f.openLateInstalments());
    }

    private static RecordFacts withoutLoan(RecordFacts f, LoanClass c) {
        return new RecordFacts(f.onTimeSavingMonths(), f.missedSavingMonths(),
                c == LoanClass.PLAN ? Math.max(0, f.loansRepaidOnPlan() - 1) : f.loansRepaidOnPlan(),
                c == LoanClass.OFF_PLAN ? Math.max(0, f.loansRepaidOffPlan() - 1) : f.loansRepaidOffPlan(),
                f.instalmentsOnTime(), f.instalmentsLate(), f.openLateInstalments());
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
        /** Set when a late-arriving fact undoes a rise; see {@link #retract}. */
        RecordLevel corrected;

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
