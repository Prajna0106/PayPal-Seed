package com.paypalseed.savings.application.ports;

import com.paypalseed.savings.api.dto.DepositFact;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Remembers which PayPal payments were credited to a goal, so an event delivered twice credits once. */
public interface CreditedPayments {

    enum Source {
        SUBSCRIPTION,
        TOPUP
    }

    /** True the first time {@code paypalTxnId} is claimed; false when it was credited before. */
    boolean claim(String paypalTxnId, UUID goalId, long amountCents, Source source);

    /** One payment credited to a goal. */
    record Credit(Source source, long amountCents, Instant creditedAt) {
    }

    /** The goal's credited payments, newest first, at most {@code limit}. */
    default List<Credit> newestOfGoal(UUID goalId, int limit) {
        return List.of();
    }

    /** Members who have a credited payment, after {@code afterMemberId} (null starts at the beginning), in id order. */
    default List<String> membersWithCredits(String afterMemberId, int limit) {
        return List.of();
    }

    /** Every payment credited to any goal of the member, oldest first. */
    default List<DepositFact> creditsOfMember(String memberId) {
        return List.of();
    }
}
