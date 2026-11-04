package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One deposit that reached a goal, for the savings timeline. {@code source} is {@code SUBSCRIPTION} (the monthly
 * deposit) or {@code TOPUP} (a one-off from PayPal or Venmo). It carries no PayPal reference.
 */
public record GoalDepositResponse(String source, BigDecimal amount, long amountCents, String currency, Instant creditedAt) {
}
