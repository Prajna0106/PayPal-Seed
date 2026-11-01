package com.paypalseed.engines.rules.record;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Level policy (ADR 0014). Pure and versioned: the same facts always give the same level.
 *
 * <p>Rules:
 * <ul>
 *   <li>A level is the highest rung whose thresholds the facts meet. Every rung above NEW also needs every missed
 *       instalment paid (no unresolved late beyond grace).</li>
 *   <li>{@link #next} never lowers a level. The only way down is {@link #afterDefault()}: hardship, one late
 *       payment inside grace, or a stretch without saving never lower a level.</li>
 *   <li>More good history never lowers {@link #evaluate}: the thresholds are floors on counts, and the on-time
 *       share only rises with more on-time events.</li>
 * </ul>
 */
public final class RecordLevels {

    public static final String POLICY_VERSION = "record-v1";

    /** An instalment is on time when paid no later than this many days after its due date (repayment-v1 grace). */
    public static final int GRACE_DAYS = 5;

    /** What a member must show to hold a level. */
    public record Requirement(RecordLevel level, int onTimeSavingMonths, int loansRepaidOnPlan, int minOnTimeBps) {
        boolean met(RecordFacts f) {
            return f.onTimeSavingMonths() >= onTimeSavingMonths
                    && f.loansRepaidOnPlan() >= loansRepaidOnPlan
                    && f.onTimeBps() >= minOnTimeBps
                    && f.openLateInstalments() == 0;
        }
    }

    /** What is still missing for the next level. Zero and false everywhere means the thresholds are met. */
    public record NextStep(RecordLevel target, int onTimeSavingMonthsToGo, int loansRepaidOnPlanToGo,
                           int requiredOnTimeBps, boolean onTimeShareTooLow, boolean latePaymentOpen) {
    }

    private static final List<Requirement> LADDER = List.of(
            new Requirement(RecordLevel.BUILDING, 3, 0, 7_500),
            new Requirement(RecordLevel.STEADY, 6, 1, 8_500),
            new Requirement(RecordLevel.TRUSTED, 9, 2, 9_000),
            new Requirement(RecordLevel.EXEMPLARY, 12, 3, 9_500));

    private RecordLevels() {
    }

    public static List<Requirement> ladder() {
        return LADDER;
    }

    public static boolean paidOnTime(int daysLate) {
        return daysLate <= GRACE_DAYS;
    }

    /** The highest level the facts support, ignoring where the member stands now. */
    public static RecordLevel evaluate(RecordFacts facts) {
        Objects.requireNonNull(facts, "facts");
        RecordLevel level = RecordLevel.NEW;
        for (Requirement r : LADDER) {
            if (r.met(facts)) {
                level = r.level();
            }
        }
        return level;
    }

    /** The level after new facts: it can stay or rise, never fall. */
    public static RecordLevel next(RecordLevel current, RecordFacts facts) {
        Objects.requireNonNull(current, "current");
        RecordLevel evaluated = evaluate(facts);
        return evaluated.atLeast(current) ? evaluated : current;
    }

    /** A loan default is the only fact that lowers a level, and it starts the climb again from the bottom. */
    public static RecordLevel afterDefault() {
        return RecordLevel.NEW;
    }

    public static Optional<NextStep> nextStep(RecordLevel current, RecordFacts facts) {
        Objects.requireNonNull(facts, "facts");
        return current.next().map(target -> {
            Requirement r = LADDER.stream().filter(x -> x.level() == target).findFirst().orElseThrow();
            return new NextStep(target, Math.max(0, r.onTimeSavingMonths() - facts.onTimeSavingMonths()),
                    Math.max(0, r.loansRepaidOnPlan() - facts.loansRepaidOnPlan()), r.minOnTimeBps(),
                    facts.onTimeBps() < r.minOnTimeBps(), facts.openLateInstalments() > 0);
        });
    }
}
