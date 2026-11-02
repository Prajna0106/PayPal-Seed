package com.paypalseed.savings.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a withdrawal is on its way out through PayPal. {@code REQUESTED} holds the money in reserve;
 * {@code SUCCEEDED} means it reached the member and was booked; {@code FAILED} gave the reserve back;
 * {@code RETURNED} means PayPal took a delivered (or unclaimed) payout back, so the member has the money
 * in the goal again. {@code FAILED} and {@code RETURNED} are final.
 */
public enum WithdrawalStatus {
    REQUESTED,
    SUCCEEDED,
    FAILED,
    RETURNED;

    private static final Set<WithdrawalStatus> FROM_REQUESTED = EnumSet.of(SUCCEEDED, FAILED, RETURNED);
    private static final Set<WithdrawalStatus> FROM_SUCCEEDED = EnumSet.of(RETURNED);

    public boolean canMoveTo(WithdrawalStatus next) {
        return switch (this) {
            case REQUESTED -> FROM_REQUESTED.contains(next);
            case SUCCEEDED -> FROM_SUCCEEDED.contains(next);
            case FAILED, RETURNED -> false;
        };
    }

    public boolean isFinal() {
        return this == FAILED || this == RETURNED;
    }
}
