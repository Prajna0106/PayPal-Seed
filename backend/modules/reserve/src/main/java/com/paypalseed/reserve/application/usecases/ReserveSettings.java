package com.paypalseed.reserve.application.usecases;

import com.paypalseed.reserve.domain.model.CoverPolicy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runtime limits of the reserve. The defaults are deliberately small: a cover is a bridge, not a grant. */
@Component
public class ReserveSettings {

    private final CoverPolicy coverPolicy;
    private final long maxDonationCents;
    private final Duration decisionSla;

    public ReserveSettings(@Value("${seed.reserve.cover.max-per-request-cents:50000}") long maxPerRequestCents,
                           @Value("${seed.reserve.cover.max-per-member-year-cents:100000}") long maxPerMemberYearCents,
                           @Value("${seed.reserve.donation.max-cents:10000000}") long maxDonationCents,
                           @Value("${seed.reserve.cover.decision-sla:PT48H}") Duration decisionSla) {
        this.coverPolicy = new CoverPolicy(maxPerRequestCents, maxPerMemberYearCents);
        if (maxDonationCents <= 0) {
            throw new IllegalArgumentException("seed.reserve.donation.max-cents must be positive");
        }
        this.maxDonationCents = maxDonationCents;
        this.decisionSla = decisionSla;
    }

    public CoverPolicy coverPolicy() {
        return coverPolicy;
    }

    /** One gift above this is refused as a likely mistake (a typo in cents, a test capture). */
    public long maxDonationCents() {
        return maxDonationCents;
    }

    /** How long officers have to decide before the approval request is overdue. */
    public Duration decisionSla() {
        return decisionSla;
    }
}
