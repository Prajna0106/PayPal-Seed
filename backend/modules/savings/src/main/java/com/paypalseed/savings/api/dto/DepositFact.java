package com.paypalseed.savings.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A deposit credited to a goal, for a read model that is rebuilt from the money itself (desk-query rebuilds its saving
 * months from these). {@code depositKey} is the PayPal transaction the money came with; it is the same key the
 * {@code DepositReceivedEvent} carries as {@code payPalCaptureId}, so a replay and a rebuild agree.
 */
public record DepositFact(String depositKey, String memberId, UUID goalId, long amountCents, Instant creditedAt) {
}
