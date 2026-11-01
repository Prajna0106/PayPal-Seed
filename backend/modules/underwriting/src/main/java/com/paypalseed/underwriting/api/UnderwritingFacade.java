package com.paypalseed.underwriting.api;

import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What other modules (F3 Fair Loan Check, F5 hardship, agent tools) may ask of underwriting. */
public interface UnderwritingFacade {

    /**
     * The member's consented income as 13 weekly totals ending the day before {@code asOf}. Empty without active
     * consent: no consent means no income signal, never a risk.
     */
    Optional<IncomeSummary> incomeSummary(UUID memberId, LocalDate asOf);

    default ConsentStatusView grantConsent(UUID memberId) {
        return new ConsentStatusView(memberId, true, null, List.of(), List.of());
    }

    default ConsentStatusView revokeConsent(UUID memberId) {
        return new ConsentStatusView(memberId, false, null, List.of(), List.of());
    }

    default ConsentStatusView getConsentStatus(UUID memberId) {
        return new ConsentStatusView(memberId, false, null, List.of(), List.of());
    }

    default Optional<FairCheckCardView> findAssessment(UUID assessmentId) {
        return Optional.empty();
    }
}
