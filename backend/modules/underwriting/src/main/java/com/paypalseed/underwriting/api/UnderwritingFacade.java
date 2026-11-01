package com.paypalseed.underwriting.api;

import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** What other modules (F5 hardship, agent tools) may ask of underwriting. */
public interface UnderwritingFacade {

    /**
     * The member's consented income as 13 weekly totals ending the day before {@code asOf}. Empty without active
     * consent: no consent means no income signal, never a risk.
     */
    Optional<IncomeSummary> incomeSummary(UUID memberId, LocalDate asOf);
}
