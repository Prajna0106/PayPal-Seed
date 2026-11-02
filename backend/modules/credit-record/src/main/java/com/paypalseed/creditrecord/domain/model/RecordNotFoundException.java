package com.paypalseed.creditrecord.domain.model;

import com.paypalseed.platform.kernel.DomainException;

/**
 * The one refusal for a share token that is unknown, expired, revoked or tampered. A type of its own so the public
 * route can answer it with a fixed body that cannot reveal which of those it was.
 */
public final class RecordNotFoundException extends DomainException {

    public RecordNotFoundException() {
        super(RecordErrors.NOT_FOUND, "No such record");
    }
}
