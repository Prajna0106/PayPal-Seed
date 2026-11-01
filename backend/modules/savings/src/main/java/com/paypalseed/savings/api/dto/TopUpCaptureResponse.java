package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The capture was asked for. The goal is credited when PayPal confirms the money (one event, whichever of the
 * capture call or the webhook reports it first), so read the goal again to see the new balance.
 */
public record TopUpCaptureResponse(
    UUID goalId,
    String orderId,
    String status,
    BigDecimal amount,
    String currency
) {
}
