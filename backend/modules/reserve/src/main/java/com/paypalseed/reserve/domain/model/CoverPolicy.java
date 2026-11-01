package com.paypalseed.reserve.domain.model;

import com.paypalseed.platform.kernel.DomainException;
import java.util.Map;

/** The size limits of a cover: one request, and one member over a calendar year. */
public record CoverPolicy(long maxPerRequestCents, long maxPerMemberYearCents) {

    public CoverPolicy {
        if (maxPerRequestCents <= 0 || maxPerMemberYearCents < maxPerRequestCents) {
            throw new IllegalArgumentException("limits must be positive and the yearly limit at least the request limit");
        }
    }

    /** {@code alreadyCommittedCents}: the member's approved and pending covers in the same calendar year. */
    public void check(long amountCents, long alreadyCommittedCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        if (amountCents > maxPerRequestCents) {
            throw new DomainException(ReserveErrors.COVER_AMOUNT_EXCEEDS_REQUEST_CAP,
                    "A cover request is limited to " + maxPerRequestCents + " cents",
                    Map.of("maxPerRequestCents", maxPerRequestCents));
        }
        long remaining = Math.max(0, maxPerMemberYearCents - alreadyCommittedCents);
        if (amountCents > remaining) {
            throw new DomainException(ReserveErrors.COVER_MEMBER_CAP_EXCEEDED,
                    "This member can still receive " + remaining + " cents of cover this year",
                    Map.of("remainingThisYearCents", remaining));
        }
    }
}
