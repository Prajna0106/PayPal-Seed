package com.paypalseed.savings.api.events;

import com.paypalseed.platform.kernel.Money;

import java.time.Instant;
import java.util.UUID;

public record GoalCreatedEvent(
    UUID eventId,
    String memberId,
    UUID goalId,
    String name,
    Money monthlyAmount,
    Money targetAmount,
    Instant timestamp
) {
    public static GoalCreatedEvent now(String memberId, UUID goalId, String name, Money monthlyAmount, Money targetAmount) {
        return new GoalCreatedEvent(UUID.randomUUID(), memberId, goalId, name, monthlyAmount, targetAmount, Instant.now());
    }
}
