package com.paypalseed.engines.rules.hardship;

import java.util.Objects;
import java.util.Optional;

/**
 * Everything the scorecard reads. All of it is the member's own consented data and their own loan
 * behaviour: no location, contacts, social or demographic input exists to pass in.
 *
 * @param income empty when there is no consented history (no income signal can fire)
 */
public record ScorecardInput(Optional<IncomeFeatures> income, long instalmentCents, int failedChargesLast14Days,
                             boolean failedTwiceOnTarget, boolean paydaySlip, int dpd, boolean lateWithin90Days,
                             boolean savingsStress, boolean memberRequested) {

    public ScorecardInput {
        Objects.requireNonNull(income, "income");
        HardshipPolicy.require(instalmentCents > 0, "instalmentCents must be positive");
        HardshipPolicy.require(failedChargesLast14Days >= 0, "failedChargesLast14Days must not be negative");
        HardshipPolicy.require(dpd >= 0, "dpd must not be negative");
    }
}
