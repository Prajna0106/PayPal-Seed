package com.paypalseed.savings.domain.model;

import com.paypalseed.platform.kernel.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One withdrawal from a goal: the reserve, the payout that follows it, and how that ended. */
public final class Withdrawal {

    private final UUID id;
    private final UUID goalId;
    private final String memberId;
    private final String idempotencyKey;
    private final Money amount;
    private final SavingsWallet wallet;
    private final Instant createdAt;
    private WithdrawalStatus status;
    private String failureReason;
    private String paypalTransactionId;
    private Instant updatedAt;

    public Withdrawal(UUID id, UUID goalId, String memberId, String idempotencyKey, Money amount, SavingsWallet wallet,
                      WithdrawalStatus status, String failureReason, String paypalTransactionId, Instant createdAt,
                      Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.goalId = Objects.requireNonNull(goalId, "goalId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.amount = Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        }
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        this.status = Objects.requireNonNull(status, "status");
        this.failureReason = failureReason;
        this.paypalTransactionId = paypalTransactionId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Withdrawal request(UUID id, UUID goalId, String memberId, String idempotencyKey, Money amount,
                                     SavingsWallet wallet, Instant now) {
        return new Withdrawal(id, goalId, memberId, idempotencyKey, amount, wallet, WithdrawalStatus.REQUESTED, null, null, now, now);
    }

    /** True when the withdrawal moved; false when it is already there (a replayed event). Illegal moves throw. */
    public boolean moveTo(WithdrawalStatus next, String reason, String transactionId, Instant now) {
        if (status == next) {
            return false;
        }
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("Withdrawal " + id + " cannot go from " + status + " to " + next);
        }
        this.status = next;
        if (reason != null) {
            this.failureReason = reason;
        }
        if (transactionId != null) {
            this.paypalTransactionId = transactionId;
        }
        this.updatedAt = now;
        return true;
    }

    /** The same request again? A reused key with another amount or wallet is a different request. */
    public boolean matches(Money requestedAmount, SavingsWallet requestedWallet) {
        return amount.equals(requestedAmount) && wallet == requestedWallet;
    }

    public UUID getId() { return id; }
    public UUID getGoalId() { return goalId; }
    public String getMemberId() { return memberId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Money getAmount() { return amount; }
    public SavingsWallet getWallet() { return wallet; }
    public WithdrawalStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getPaypalTransactionId() { return paypalTransactionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
