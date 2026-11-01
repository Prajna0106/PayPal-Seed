package com.paypalseed.fraud.domain.model;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One thing that happened, as the rules read it. {@code refHash} is the salted hash of a stable identifier, or null.
 * Facts order by time, then by id, so two facts at the same instant keep the order they arrived in.
 */
public record Fact(long id, UUID memberId, FactKind kind, Instant occurredAt, Long amountCents, String refHash,
                   Map<String, String> attributes) {

    public Fact {
        attributes = Map.copyOf(attributes);
    }

    public long amountOrZero() {
        return amountCents == null ? 0 : amountCents;
    }
}
