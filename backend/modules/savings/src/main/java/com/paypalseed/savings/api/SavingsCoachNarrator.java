package com.paypalseed.savings.api;

import java.util.Objects;

/**
 * Port for generating grounded, plain-language savings & microloan coaching narratives
 * for Features 1 & 2 (ADR 0009, ADR 0010).
 *
 * <p>All monetary figures, loan unlock amounts, and repayment terms are computed deterministically
 * by {@code SuggestSavingsPlanUseCase} before calling this port. Implementations in
 * {@code agent-gateway} call {@code ModelGateway} under kill-switch and grounding checks,
 * falling back to the deterministic template texts when disabled or when grounding fails.
 */
public interface SavingsCoachNarrator {

    record CoachNarrationInput(
        String memberId,
        String goalName,
        long targetAmountCents,
        long statedMonthlyIncomeCents,
        long statedMonthlyExpensesCents,
        long disposableMonthlyIncomeCents,
        long suggestedMonthlySavingCents,
        int streakMonthsToUnlock,
        long totalSavedAfterStreakCents,
        String unlockedTier,
        long unlockedLoanAmountCents,
        long maxMonthlyInstalmentCents,
        int suggestedLoanTermMonths,
        long suggestedMonthlyInstalmentCents,
        String templateMessageEn,
        String templateMessageEs
    ) {
        public CoachNarrationInput {
            Objects.requireNonNull(templateMessageEn, "templateMessageEn is required");
            Objects.requireNonNull(templateMessageEs, "templateMessageEs is required");
        }
    }

    record CoachNarrationResult(
        String messageEn,
        String messageEs,
        boolean modelGenerated
    ) {
        public CoachNarrationResult {
            Objects.requireNonNull(messageEn, "messageEn is required");
            Objects.requireNonNull(messageEs, "messageEs is required");
        }
    }

    CoachNarrationResult narrate(CoachNarrationInput input);
}
