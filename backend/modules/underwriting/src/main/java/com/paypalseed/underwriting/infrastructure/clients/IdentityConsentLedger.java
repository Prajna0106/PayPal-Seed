package com.paypalseed.underwriting.infrastructure.clients;

import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentGrant;
import com.paypalseed.underwriting.application.ports.MemberConsentLedger;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the fair-check consent into identity's ledger. Identity does the idempotency (the same member, type and
 * version while active changes nothing). The member is looked up first so that an unknown member is skipped instead
 * of raising a 404 inside the caller's transaction, which would mark it rollback-only.
 */
@Component
class IdentityConsentLedger implements MemberConsentLedger {

    private final IdentityFacade identity;

    IdentityConsentLedger(IdentityFacade identity) {
        this.identity = identity;
    }

    @Override
    public void fairCheckGiven(UUID memberId) {
        if (identity.member(memberId).isPresent()) {
            identity.grantConsent(memberId, new ConsentGrant(ConsentType.FAIR_CHECK, null));
        }
    }

    @Override
    public void closed(UUID memberId) {
        if (identity.member(memberId).isPresent()) {
            identity.revokeConsent(memberId, ConsentType.FAIR_CHECK);
            identity.revokeConsent(memberId, ConsentType.INCOME_READ);
        }
    }

    @Override
    public boolean withdrawn(UUID memberId, Line line) {
        if (identity.member(memberId).isEmpty()) {
            return true;
        }
        ConsentType own = line == Line.FAIR_CHECK ? ConsentType.FAIR_CHECK : ConsentType.INCOME_READ;
        ConsentType other = line == Line.FAIR_CHECK ? ConsentType.INCOME_READ : ConsentType.FAIR_CHECK;
        if (identity.hasActiveConsent(memberId, own)) {
            return false;
        }
        identity.revokeConsent(memberId, other);
        return true;
    }
}
