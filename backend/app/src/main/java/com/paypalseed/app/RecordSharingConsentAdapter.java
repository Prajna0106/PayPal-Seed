package com.paypalseed.app;

import com.paypalseed.creditrecord.api.RecordSharingConsent;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.ConsentType;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Answers the credit record's question "did this member agree to share?" from identity's consent ledger (ADR 0025).
 * The record imports no other business module (ADR 0014), so this wiring module joins the two.
 *
 * <p>Identity keeps one active line per consent type, and a member who agreed to an older text keeps that line until
 * they agree again. The text in force is the same property identity reads, so an out-of-date agreement does not count.
 */
@Component
class RecordSharingConsentAdapter implements RecordSharingConsent {

    private final IdentityFacade identity;
    private final String currentVersion;

    RecordSharingConsentAdapter(IdentityFacade identity,
                                @Value("${seed.identity.consent.record-sharing-version:2026-11}") String currentVersion) {
        this.identity = identity;
        this.currentVersion = currentVersion;
    }

    @Override
    public boolean granted(UUID memberId) {
        if (identity.member(memberId).isEmpty()) {
            return false;
        }
        return identity.consents(memberId).stream().anyMatch(c -> c.type() == ConsentType.RECORD_SHARING && c.active()
                && currentVersion.equals(c.version()));
    }
}
