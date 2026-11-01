package com.paypalseed.underwriting.api;

import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** What other modules (F2 microloans, F3 Fair Loan Check, F5 hardship, agent tools) may ask of underwriting. */
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

    /**
     * Resolves the member's verified monthly income in cents from their latest Fair Loan Check assessment
     * or their 13-week consented PayPal income summary.
     */
    default OptionalLong verifiedMonthlyIncomeCents(UUID memberId, LocalDate asOf) {
        return OptionalLong.empty();
    }

    /**
     * Runs and records an auditable Fair Loan Check assessment linked to a submitted microloan application.
     */
    default Optional<FairCheckCardView> assessForLoanApplication(
        UUID memberId,
        UUID applicationId,
        String purpose,
        long requestedAmountCents,
        int requestedTermMonths,
        long statedMonthlyIncomeCents,
        int completedLoansOnTime,
        boolean hasActiveLoan
    ) {
        return Optional.empty();
    }
}
