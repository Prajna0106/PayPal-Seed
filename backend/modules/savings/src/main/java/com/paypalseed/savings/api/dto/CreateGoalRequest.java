package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Asks for a savings goal. The backend creates the PayPal subscription: send an {@code Idempotency-Key}
 * header and no subscription id, and the response carries the {@code approvalUrl} the member must visit.
 *
 * @param memberId            ignored when the caller is signed in (the signed-in member is used)
 * @param payPalSubscriptionId <b>Legacy.</b> A subscription id the client made itself. Accepted only in the dev,
 *                            test and demo profiles (it makes the goal active at once, with no approval step);
 *                            any other profile answers {@code legacy-subscription-not-allowed}.
 * @param wallet              {@code PAYPAL} (default) saves by monthly subscription; {@code VENMO} saves by one-off
 *                            top-ups, because PayPal Subscriptions cannot be funded with Venmo
 */
public record CreateGoalRequest(
    String memberId,
    String name,
    BigDecimal targetAmount,
    BigDecimal monthlyAmount,
    String currency,
    LocalDate targetDate,
    String payPalSubscriptionId,
    String wallet
) {
    /** The shape before the backend made subscriptions itself. */
    public CreateGoalRequest(
        String memberId,
        String name,
        BigDecimal targetAmount,
        BigDecimal monthlyAmount,
        String currency,
        LocalDate targetDate,
        String payPalSubscriptionId
    ) {
        this(memberId, name, targetAmount, monthlyAmount, currency, targetDate, payPalSubscriptionId, null);
    }

    public String getEffectiveCurrency() {
        return currency != null && !currency.trim().isEmpty() ? currency : "USD";
    }
}
