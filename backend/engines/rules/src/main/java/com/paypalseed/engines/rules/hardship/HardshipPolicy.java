package com.paypalseed.engines.rules.hardship;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Hardship policy (03-algorithms §1). Versioned like F4's {@code RepaymentPolicy}: a case records the
 * version it was decided under, so a later change never rewrites an old decision. Shares of money are
 * basis points, so no decision depends on floating point.
 */
public record HardshipPolicy(String version, String scorecardVersion, Scoring scoring, Options options,
                             Outreach outreach, Timing timing) {

    public HardshipPolicy {
        requireText(version, "version");
        requireText(scorecardVersion, "scorecardVersion");
        Objects.requireNonNull(scoring, "scoring");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(outreach, "outreach");
        Objects.requireNonNull(timing, "timing");
    }

    /** Signal thresholds and points. {@code coverageCriticalBps < coverageLowBps}: lower coverage is worse. */
    public record Scoring(int lookbackWeeks, int recentWeeks, int minHistoryWeeks, int scanLeadDays, int graceDays,
                          int watchAt, int likelyMissAt, int imminentAt,
                          int incomeDropModerateBps, int incomeDropSevereBps,
                          int usableShareBps, int coverageLowBps, int coverageCriticalBps,
                          int persistenceBelowBps, int persistenceWeeks, int hysteresisScans,
                          int failedChargeLookbackDays, int lateLookbackDays, int savingsStressLookbackDays,
                          Points points) {

        public Scoring {
            require(lookbackWeeks >= recentWeeks && recentWeeks > 0, "lookbackWeeks must cover recentWeeks");
            require(minHistoryWeeks > 0 && minHistoryWeeks <= lookbackWeeks, "minHistoryWeeks out of range");
            require(scanLeadDays >= 0 && graceDays >= 0, "scan window must not be negative");
            require(0 < watchAt && watchAt < likelyMissAt && likelyMissAt < imminentAt, "level thresholds must rise");
            require(0 < incomeDropModerateBps && incomeDropModerateBps < incomeDropSevereBps && incomeDropSevereBps <= 10_000,
                    "income drop thresholds must rise within 0..10000 bps");
            require(usableShareBps > 0 && usableShareBps <= 10_000, "usableShareBps out of range");
            require(0 < coverageCriticalBps && coverageCriticalBps < coverageLowBps, "coverage thresholds must rise");
            require(persistenceBelowBps > 0 && persistenceBelowBps <= 10_000, "persistenceBelowBps out of range");
            require(persistenceWeeks > 0 && persistenceWeeks <= recentWeeks, "persistenceWeeks out of range");
            require(hysteresisScans >= 1, "hysteresisScans must be at least 1");
            require(failedChargeLookbackDays > 0 && lateLookbackDays > 0 && savingsStressLookbackDays > 0,
                    "lookbacks must be positive");
            Objects.requireNonNull(points, "points");
        }
    }

    /** Points per signal; each "severe" variant replaces, never adds to, its milder sibling. */
    public record Points(int incomeDropModerate, int incomeDropSevere, int lowCoverage, int criticalCoverage,
                         int autopayFailed, int autopayFailedTwice, int paydaySlip, int inGrace, int recentLate,
                         int savingsStress) {

        public Points {
            require(0 < incomeDropModerate && incomeDropModerate <= incomeDropSevere, "income drop points must rise");
            require(0 < lowCoverage && lowCoverage <= criticalCoverage, "coverage points must rise");
            require(0 < autopayFailed && autopayFailed <= autopayFailedTwice, "autopay points must rise");
            require(0 < recentLate && recentLate <= inGrace, "lateness points must rise");
            require(paydaySlip > 0 && savingsStress > 0, "points must be positive");
        }
    }

    /** Option limits and the frequency caps of 03 §7. */
    public record Options(int maxMoveDays, int maxExtraMonths, int maxBurdenBps, int paymentBurdenBps,
                          String ineligibleBucket, int capWindowDays, int moveDateCapPerWindow, int pauseCapPerWindow,
                          int pauseCapPerLoan, int restructureCapPerLoan) {

        public Options {
            require(maxMoveDays > 0 && maxExtraMonths > 0, "option ranges must be positive");
            require(maxBurdenBps > 0 && maxBurdenBps <= 10_000, "maxBurdenBps out of range");
            require(paymentBurdenBps > 0 && paymentBurdenBps <= 10_000, "paymentBurdenBps out of range");
            requireText(ineligibleBucket, "ineligibleBucket");
            require(capWindowDays > 0, "capWindowDays must be positive");
            require(moveDateCapPerWindow > 0 && pauseCapPerWindow > 0 && pauseCapPerLoan >= pauseCapPerWindow
                    && restructureCapPerLoan > 0, "caps must be positive");
        }
    }

    /** Outreach limits: a member is never contacted more than this, whatever the score says. */
    public record Outreach(int cooldownDays, int perInstalment, int remindersPerInstalment, int per30Days) {

        public Outreach {
            require(cooldownDays >= 0 && perInstalment > 0 && remindersPerInstalment >= 0 && per30Days >= perInstalment,
                    "outreach limits out of range");
        }
    }

    /** Holds, decision SLA (business hours in the program zone) and offer expiry. */
    public record Timing(int holdWindowDays, int holdDays, int holdExtensionDays, int slaBusinessHours,
                         int slaPriorityHours, int priorityDueWithinDays, int offerExpiryDaysAfterDue, ZoneId zone,
                         int businessDayStartHour, int businessDayEndHour) {

        public Timing {
            require(holdWindowDays > 0 && holdDays > 0 && holdExtensionDays >= 0, "hold limits out of range");
            require(0 < slaPriorityHours && slaPriorityHours <= slaBusinessHours, "SLA budgets out of range");
            require(priorityDueWithinDays >= 0 && offerExpiryDaysAfterDue >= 0, "day counts must not be negative");
            Objects.requireNonNull(zone, "zone");
            require(0 <= businessDayStartHour && businessDayStartHour < businessDayEndHour && businessDayEndHour <= 24,
                    "business day out of range");
        }

        public int businessDayHours() {
            return businessDayEndHour - businessDayStartHour;
        }
    }

    public static HardshipPolicy v1() {
        return new HardshipPolicy("hardship-v1", "scorecard-v1",
                new Scoring(13, 4, 8, 10, 5,
                        3, 6, 9,
                        3_000, 5_000,
                        3_000, 10_000, 6_000,
                        7_000, 3, 2,
                        14, 90, 30,
                        new Points(3, 5, 2, 4, 3, 5, 2, 3, 2, 1)),
                new Options(14, 6, 2_500, 1_500, "DPD_60_PLUS", 183, 2, 1, 2, 1),
                new Outreach(14, 1, 1, 2),
                new Timing(3, 3, 2, 10, 4, 2, 5, ZoneId.of("America/Los_Angeles"), 8, 18));
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
