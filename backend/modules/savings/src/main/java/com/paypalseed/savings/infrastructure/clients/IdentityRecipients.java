package com.paypalseed.savings.infrastructure.clients;

import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.savings.application.ports.PayoutRecipients;
import com.paypalseed.savings.domain.model.SavingsWallet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The member's PayPal account and wallet choice come from the identity module. */
@Component
class IdentityRecipients implements PayoutRecipients {

    private final IdentityFacade identity;

    IdentityRecipients(IdentityFacade identity) {
        this.identity = identity;
    }

    @Override
    public Optional<Recipient> of(UUID memberId) {
        return identity.payoutRecipient(memberId).flatMap(r -> {
            String payerId = blankToNull(r.payerId());
            String email = blankToNull(r.email());
            if (payerId == null && email == null) {
                return Optional.empty();
            }
            SavingsWallet preferred = identity.member(memberId)
                .map(m -> m.walletPreference() == null ? SavingsWallet.PAYPAL : SavingsWallet.valueOf(m.walletPreference().name()))
                .orElse(SavingsWallet.PAYPAL);
            return Optional.of(new Recipient(payerId, email, preferred));
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
