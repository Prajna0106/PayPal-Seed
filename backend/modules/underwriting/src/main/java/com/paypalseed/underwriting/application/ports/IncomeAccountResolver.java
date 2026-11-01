package com.paypalseed.underwriting.application.ports;

import java.util.Optional;
import java.util.UUID;

/** Maps a Seed member to the PayPal account reference their income is read from. */
public interface IncomeAccountResolver {

    /**
     * The reference to read with, which may be blank for "the program account" (the sandbox demo). Empty when the
     * member has not linked a PayPal account, which the caller reports as {@code paypal-account-not-linked}.
     */
    Optional<String> accountOf(UUID memberId);
}
