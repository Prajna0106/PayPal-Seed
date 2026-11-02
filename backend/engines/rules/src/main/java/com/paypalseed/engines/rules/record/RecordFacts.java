package com.paypalseed.engines.rules.record;

/**
 * What the record counts. Every field is a non-negative count, so no input can produce a negative ratio.
 *
 * <p>An instalment paid within grace counts as on time. Hardship never adds to a "late" or "missed" field: an
 * instalment on an approved plan is simply on time or not against its new date.
 *
 * @param onTimeSavingMonths    calendar months with at least one deposit
 * @param missedSavingMonths    months with none, after saving had started
 * @param loansRepaidOnPlan     loans closed with no recorded miss (a hardship plan counts as on plan)
 * @param loansRepaidOffPlan    loans closed after at least one recorded miss
 * @param instalmentsOnTime     instalments paid no later than the end of grace
 * @param instalmentsLate       instalments paid after grace ran out
 * @param openLateInstalments   missed instalments still unpaid
 */
public record RecordFacts(int onTimeSavingMonths, int missedSavingMonths, int loansRepaidOnPlan, int loansRepaidOffPlan,
                          int instalmentsOnTime, int instalmentsLate, int openLateInstalments) {

    public static final RecordFacts EMPTY = new RecordFacts(0, 0, 0, 0, 0, 0, 0);

    /** Ten thousand basis points is a perfect record. */
    public static final int FULL_BPS = 10_000;

    public RecordFacts {
        if (onTimeSavingMonths < 0 || missedSavingMonths < 0 || loansRepaidOnPlan < 0 || loansRepaidOffPlan < 0
                || instalmentsOnTime < 0 || instalmentsLate < 0 || openLateInstalments < 0) {
            throw new IllegalArgumentException("record counts cannot be negative");
        }
    }

    /**
     * Share of on-time events (saving months and instalments) in basis points, rounded down. A record with no
     * events yet has nothing against it and reads as full.
     */
    public int onTimeBps() {
        long good = (long) onTimeSavingMonths + instalmentsOnTime;
        long total = good + missedSavingMonths + instalmentsLate + openLateInstalments;
        return total == 0 ? FULL_BPS : (int) (good * FULL_BPS / total);
    }

    public RecordFacts plusSavingMonthOnTime() {
        return new RecordFacts(add(onTimeSavingMonths, 1), missedSavingMonths, loansRepaidOnPlan, loansRepaidOffPlan,
                instalmentsOnTime, instalmentsLate, openLateInstalments);
    }

    public RecordFacts plusSavingMonthsMissed(int months) {
        return new RecordFacts(onTimeSavingMonths, add(missedSavingMonths, months), loansRepaidOnPlan, loansRepaidOffPlan,
                instalmentsOnTime, instalmentsLate, openLateInstalments);
    }

    public RecordFacts plusInstalmentOnTime() {
        return new RecordFacts(onTimeSavingMonths, missedSavingMonths, loansRepaidOnPlan, loansRepaidOffPlan,
                add(instalmentsOnTime, 1), instalmentsLate, openLateInstalments);
    }

    public RecordFacts plusInstalmentLate() {
        return new RecordFacts(onTimeSavingMonths, missedSavingMonths, loansRepaidOnPlan, loansRepaidOffPlan,
                instalmentsOnTime, add(instalmentsLate, 1), openLateInstalments);
    }

    public RecordFacts plusLoanRepaid(boolean onPlan) {
        return new RecordFacts(onTimeSavingMonths, missedSavingMonths, onPlan ? add(loansRepaidOnPlan, 1) : loansRepaidOnPlan,
                onPlan ? loansRepaidOffPlan : add(loansRepaidOffPlan, 1), instalmentsOnTime, instalmentsLate,
                openLateInstalments);
    }

    public RecordFacts withOpenLate(int open) {
        return new RecordFacts(onTimeSavingMonths, missedSavingMonths, loansRepaidOnPlan, loansRepaidOffPlan,
                instalmentsOnTime, instalmentsLate, open);
    }

    private static int add(int value, int delta) {
        return (int) Math.min(Integer.MAX_VALUE, (long) value + delta);
    }
}
