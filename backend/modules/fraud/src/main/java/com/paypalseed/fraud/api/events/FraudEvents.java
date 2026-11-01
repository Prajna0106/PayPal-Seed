package com.paypalseed.fraud.api.events;

import com.paypalseed.fraud.api.dto.FraudDtos.FlagStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain events published by fraud, inside the transaction that changed the flag. Delivery is at least once: consumers
 * dedupe on {@code eventId}. They carry codes and counts only, never the facts behind a signal.
 */
public final class FraudEvents {

    private FraudEvents() {
    }

    public record FraudFlagRaised(UUID eventId, UUID flagId, UUID memberId, int score, List<String> signalCodes,
                                  Instant occurredAt) {
        public FraudFlagRaised {
            signalCodes = List.copyOf(signalCodes);
        }
    }

    /** {@code outcome} is REVIEWED_CLEAR or CONFIRMED. */
    public record FraudFlagReviewed(UUID eventId, UUID flagId, UUID memberId, FlagStatus outcome, Instant occurredAt) {
    }
}
