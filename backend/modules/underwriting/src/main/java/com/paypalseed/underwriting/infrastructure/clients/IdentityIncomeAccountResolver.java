package com.paypalseed.underwriting.infrastructure.clients;

import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.underwriting.application.ports.IncomeAccountResolver;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A member who has signed in with PayPal has an identity record, which is what "linked" means here. The record's
 * payer id is the account reference for partner reading; {@link IdentityFacade} does not expose it yet (ADR 0018),
 * so until it does the reference is blank and payments reads the program account. That is the sandbox demo path and
 * is correct while {@code seed.paypal.transaction-search.on-behalf} stays off.
 */
@Component
class IdentityIncomeAccountResolver implements IncomeAccountResolver {

    private final IdentityFacade identity;

    IdentityIncomeAccountResolver(IdentityFacade identity) {
        this.identity = identity;
    }

    @Override
    public Optional<String> accountOf(UUID memberId) {
        return identity.member(memberId).map(member -> "");
    }
}
