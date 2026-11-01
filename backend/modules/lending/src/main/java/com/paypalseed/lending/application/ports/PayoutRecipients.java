package com.paypalseed.lending.application.ports;

import java.util.Optional;
import java.util.UUID;

/** Where a member's loan is paid: the PayPal account named by the login record. */
public interface PayoutRecipients {

    /** Either may be null, never both. Personal data: do not log it. */
    record Recipient(String payerId, String email) {
        @Override
        public String toString() {
            return "Recipient[personal data redacted]";
        }
    }

    Optional<Recipient> of(UUID memberId);
}
