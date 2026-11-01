package com.paypalseed.savings.api.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A member's goal as a read model for pages that list goals next to other modules' data. Whole cents and a currency
 * code, no PayPal reference and no approval link. {@code savedCents} is money in the goal now; money held for a
 * withdrawal that has not finished is not in it.
 */
public record MemberGoalView(UUID goalId, String name, String status, long targetCents, long savedCents,
                             long monthlyCents, String currency, LocalDate targetDate) {
}
