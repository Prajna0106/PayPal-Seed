package com.paypalseed.creditrecord.api;

/**
 * Public interface for Trust Agent narration (Feature 6: Portable Credit Record & Community Standing).
 *
 * <p>Translates deterministic tier status, on-time repayments, and streak data into
 * member explanations and a portable third-party attestation without credit bureau jargon.
 */
public interface TrustRecordNarrator {

    record TrustNarrationInput(
        String memberId,
        String currentTier,
        String nextTier,
        long currentLimitCents,
        long nextLimitCents,
        int onTimeRepaymentsCount,
        int streakMonths,
        long totalSavedCents,
        long totalRepaidCents,
        String templateSummaryEn,
        String templateSummaryEs,
        String templateAttestation,
        String templateMilestoneAdvice
    ) {}

    record TrustNarrationResult(
        String summaryEn,
        String summaryEs,
        String certificateAttestation,
        String milestoneAdvice,
        boolean modelGenerated
    ) {}

    TrustNarrationResult narrate(TrustNarrationInput input);
}
