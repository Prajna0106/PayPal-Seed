package com.paypalseed.app;

import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.events.IdentityEvents.ConsentRevoked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Withdrawing the consent to share ends every link the member made (ADR 0025). The record imports no other business
 * module (ADR 0014), so this wiring module hears identity's event and tells the record. Delivery is at least once and
 * revoking is idempotent, so a repeat finds nothing active and changes nothing.
 */
@Component
class RecordShareRevocation {

    private static final Logger log = LoggerFactory.getLogger(RecordShareRevocation.class);

    private final CreditRecordFacade record;

    RecordShareRevocation(CreditRecordFacade record) {
        this.record = record;
    }

    @ApplicationModuleListener
    void on(ConsentRevoked e) {
        if (e.type() != ConsentType.RECORD_SHARING) {
            return;
        }
        int closed = record.revokeAllShares(e.memberId());
        log.info("Record sharing consent withdrawn: {} share link(s) revoked", closed);
    }
}
