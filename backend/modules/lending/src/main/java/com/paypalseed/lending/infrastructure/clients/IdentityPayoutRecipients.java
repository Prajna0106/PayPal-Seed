package com.paypalseed.lending.infrastructure.clients;

import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.lending.application.ports.PayoutRecipients;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The member's PayPal account comes from the identity module's login record. */
@Component
class IdentityPayoutRecipients implements PayoutRecipients {

    private final IdentityFacade identity;

    IdentityPayoutRecipients(IdentityFacade identity) {
        this.identity = identity;
    }

    @Override
    public Optional<Recipient> of(UUID memberId) {
        return identity.payoutRecipient(memberId)
            .map(r -> new Recipient(blankToNull(r.payerId()), blankToNull(r.email())))
            .filter(r -> r.payerId() != null || r.email() != null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
