package com.paypalseed.savings.application.ports;

import java.util.UUID;

/** Remembers which PayPal payments were credited to a goal, so an event delivered twice credits once. */
public interface CreditedPayments {

    enum Source {
        SUBSCRIPTION,
        TOPUP
    }

    /** True the first time {@code paypalTxnId} is claimed; false when it was credited before. */
    boolean claim(String paypalTxnId, UUID goalId, long amountCents, Source source);
}
