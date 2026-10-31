package com.paypalseed.engines.rules.hardship;

import java.util.ArrayList;
import java.util.List;

/**
 * The transparent points scorecard (03 §3). Each rule is non-decreasing in its inputs, so more evidence
 * of trouble never lowers the score (PROP-H-01). Within a signal family only the stronger variant fires.
 */
public final class Scorecard {

    private Scorecard() {
    }

    public static ScoreResult score(ScorecardInput in, HardshipPolicy policy) {
        HardshipPolicy.Scoring s = policy.scoring();
        HardshipPolicy.Points pts = s.points();
        List<Signal> signals = new ArrayList<>();

        in.income().ifPresent(f -> {
            if (f.dropBps() >= s.incomeDropSevereBps()) {
                signals.add(Signal.of(Signal.INCOME_DROP_SEVERE, pts.incomeDropSevere(), "last4Cents", f.last4Cents(),
                        "baseline4Cents", f.baseline4Cents(), "dropBps", f.dropBps(), "weeksCompared", s.lookbackWeeks()));
            } else if (f.dropBps() >= s.incomeDropModerateBps()) {
                signals.add(Signal.of(Signal.INCOME_DROP_MODERATE, pts.incomeDropModerate(), "last4Cents", f.last4Cents(),
                        "baseline4Cents", f.baseline4Cents(), "dropBps", f.dropBps(), "weeksCompared", s.lookbackWeeks()));
            }
            if (f.coverageBps() < s.coverageCriticalBps()) {
                signals.add(coverage(Signal.CRITICAL_COVERAGE, pts.criticalCoverage(), f, in, s));
            } else if (f.coverageBps() < s.coverageLowBps()) {
                signals.add(coverage(Signal.LOW_COVERAGE, pts.lowCoverage(), f, in, s));
            }
        });
        if (in.failedChargesLast14Days() > 0) {
            signals.add(in.failedTwiceOnTarget()
                    ? Signal.of(Signal.AUTOPAY_FAILED_TWICE, pts.autopayFailedTwice(),
                            "failedChargesLast14Days", in.failedChargesLast14Days())
                    : Signal.of(Signal.AUTOPAY_FAILED, pts.autopayFailed(),
                            "failedChargesLast14Days", in.failedChargesLast14Days()));
        }
        if (in.paydaySlip()) {
            signals.add(Signal.of(Signal.PAYDAY_SLIP, pts.paydaySlip()));
        }
        // Any lateness scores IN_GRACE. The scan only looks at loans up to the end of grace, and keeping the
        // rule flat past grace keeps the scorecard monotonic in days past due.
        if (in.dpd() > 0) {
            signals.add(Signal.of(Signal.IN_GRACE, pts.inGrace(), "dpd", in.dpd()));
        } else if (in.lateWithin90Days()) {
            signals.add(Signal.of(Signal.RECENT_LATE, pts.recentLate(), "lookbackDays", s.lateLookbackDays()));
        }
        if (in.savingsStress()) {
            signals.add(Signal.of(Signal.SAVINGS_STRESS, pts.savingsStress(), "lookbackDays", s.savingsStressLookbackDays()));
        }
        int points = signals.stream().mapToInt(Signal::points).sum();
        if (in.memberRequested()) {
            signals.add(Signal.of(Signal.MEMBER_REQUEST, 0));
            return new ScoreResult(points, RiskLevel.IMMINENT, signals);
        }
        return new ScoreResult(points, RiskLevel.forPoints(points, s), signals);
    }

    private static Signal coverage(String code, int points, IncomeFeatures f, ScorecardInput in, HardshipPolicy.Scoring s) {
        return Signal.of(code, points, "recentMonthlyCents", f.recentMonthlyCents(), "usableShareBps", s.usableShareBps(),
                "instalmentCents", in.instalmentCents(), "coverageBps", f.coverageBps());
    }
}
