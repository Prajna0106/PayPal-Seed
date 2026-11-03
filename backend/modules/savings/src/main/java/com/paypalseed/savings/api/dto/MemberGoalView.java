package com.paypalseed.savings.api.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A member's goal as a read model for pages that list goals next to other modules' data. Whole cents and a currency
 * code, no PayPal reference and no approval link. {@code savedCents} is money in the goal now; money held for a
 * withdrawal that has not finished is not in it. {@code nextDepositDate} is the business date PayPal will bill the
 * monthly deposit next, as PayPal last reported it; null when the goal is not saving by subscription right now (paused,
 * waiting for approval, ended, Venmo) or PayPal has not said.
 */
public record MemberGoalView(UUID goalId, String name, String status, long targetCents, long savedCents,
                             long monthlyCents, String currency, LocalDate targetDate, LocalDate nextDepositDate) {

    public MemberGoalView(UUID goalId, String name, String status, long targetCents, long savedCents, long monthlyCents,
                          String currency, LocalDate targetDate) {
        this(goalId, name, status, targetCents, savedCents, monthlyCents, currency, targetDate, null);
    }
}
