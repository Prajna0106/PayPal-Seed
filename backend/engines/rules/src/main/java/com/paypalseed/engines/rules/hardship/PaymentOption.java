package com.paypalseed.engines.rules.hardship;

import java.util.OptionalLong;

/** Option D, "pay what you can": an amount sized like a new loan instalment (03 §5). */
public final class PaymentOption {

    private PaymentOption() {
    }

    /**
     * Whole dollars: {@code paymentBurdenBps} of recent monthly income, or half the instalment when income is
     * unknown. Empty when that is under $1 or not less than the instalment (then it is just a payment).
     */
    public static OptionalLong amountCents(Long recentMonthlyCents, long instalmentCents, HardshipPolicy policy) {
        HardshipPolicy.require(instalmentCents > 0, "instalmentCents must be positive");
        long amount = recentMonthlyCents == null
                ? instalmentCents / 2 / 100 * 100
                : recentMonthlyCents * policy.options().paymentBurdenBps() / 10_000 / 100 * 100;
        return amount >= 100 && amount < instalmentCents ? OptionalLong.of(amount) : OptionalLong.empty();
    }
}
