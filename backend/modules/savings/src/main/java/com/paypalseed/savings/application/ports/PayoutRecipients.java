package com.paypalseed.savings.application.ports;

import com.paypalseed.savings.domain.model.SavingsWallet;
import java.util.Optional;
import java.util.UUID;

/** Where a member's money goes when it leaves a goal. */
public interface PayoutRecipients {

    /**
     * The member's PayPal payer id and email from the login record (either may be null, never both), and the wallet
     * they prefer. Personal data: do not log it.
     */
    record Recipient(String payerId, String email, SavingsWallet preferredWallet) {
        @Override
        public String toString() {
            return "Recipient[personal data redacted]";
        }
    }

    Optional<Recipient> of(UUID memberId);
}
