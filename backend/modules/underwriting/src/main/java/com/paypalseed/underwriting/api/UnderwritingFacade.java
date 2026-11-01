package com.paypalseed.underwriting.api;

import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import java.util.Optional;
import java.util.UUID;

/**
 * Public facade for Feature 3: The Fair Loan Check (Blueprint §6).
 */
public interface UnderwritingFacade {

    ConsentStatusView grantConsent(UUID memberId);

    ConsentStatusView revokeConsent(UUID memberId);

    ConsentStatusView getConsentStatus(UUID memberId);

    Optional<FairCheckCardView> findAssessment(UUID assessmentId);
}
