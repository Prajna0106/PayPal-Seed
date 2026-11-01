package com.paypalseed.savings.domain.model;

/** Where the member saves from and is paid back into. Venmo cannot fund a subscription, only one-off top-ups. */
public enum SavingsWallet {
    PAYPAL,
    VENMO;

    public boolean supportsSubscriptions() {
        return this == PAYPAL;
    }
}
