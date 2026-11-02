package com.paypalseed.underwriting.infrastructure.events;

import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.events.IdentityEvents.ConsentRevoked;
import com.paypalseed.underwriting.application.usecases.IncomeConsentSync;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Follows the member's consent ledger in identity, so withdrawing income read really stops the fair check. */
@Component
class UnderwritingEventListener {

    private final IncomeConsentSync consents;

    UnderwritingEventListener(IncomeConsentSync consents) {
        this.consents = consents;
    }

    @ApplicationModuleListener
    void on(ConsentRevoked event) {
        if (event.type() == ConsentType.INCOME_READ) {
            consents.incomeReadWithdrawn(event.memberId());
        }
    }
}
