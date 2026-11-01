package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;

/**
 * A one-off deposit. Send an {@code Idempotency-Key} header and the response carries the {@code approvalUrl}
 * where the member pays with PayPal or Venmo; then call the capture endpoint.
 *
 * @param payPalCaptureId <b>Legacy.</b> A capture id the client made itself; accepted only in the dev, test and
 *                        demo profiles, where it credits the goal at once without calling PayPal.
 */
public record TopUpRequest(
    BigDecimal amount,
    String currency,
    String payPalCaptureId
) {
    public String getEffectiveCurrency() {
        return currency != null && !currency.trim().isEmpty() ? currency : "USD";
    }
}
