package com.paypalseed.hardship.domain.model;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import java.util.Map;

/**
 * What the member saw is no longer what would happen (HINV-14). Thrown after a new offer revision has been
 * written; callers declare {@code noRollbackFor} so the revision survives the failed request.
 */
public class OfferStaleException extends DomainException {

    public static final ErrorCode OFFER_STALE = ErrorCode.conflict("hardship-offer-stale",
            "The schedule changed since the offer was made; please confirm the new numbers");

    public OfferStaleException(String detail, Map<String, Object> details) {
        super(OFFER_STALE, detail, details);
    }
}
