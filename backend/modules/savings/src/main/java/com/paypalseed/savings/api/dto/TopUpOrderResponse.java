package com.paypalseed.savings.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** A top-up waiting for the member: open {@code approvalUrl}, approve, then capture the order. */
public record TopUpOrderResponse(
    UUID goalId,
    String orderId,
    String status,
    String approvalUrl,
    BigDecimal amount,
    String currency
) {
}
