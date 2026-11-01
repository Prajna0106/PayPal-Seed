package com.paypalseed.underwriting.api;

import java.util.List;
import java.util.UUID;

/**
 * Port for the Feature 3 Underwriting AI Agent (ADR 0009: AI Proposes, Code Computes, Human Approves).
 *
 * <p>Implemented by {@code modules/agent-gateway} so {@code modules/underwriting} has no reverse dependency
 * on the agent gateway. The deterministic {@code AssessmentEngine} computes every income, volatility, and
 * sizing figure first; this port generates the grounded plain-language explanation card and loan-officer
 * follow-up questions, falling back to deterministic templates whenever the kill switch is engaged, the
 * model is unavailable, or grounding validation fails.
 */
public interface FairCheckNarrator {

    record NarrationInput(
        UUID assessmentId,
        UUID applicationId,
        String purpose,
        boolean recommended,
        String unlockedTier,
        boolean usedFallbackStatedIncome,
        long averageMonthlyIncomeCents,
        long lowestMonthlyIncomeCents,
        String lowestIncomeMonth,
        double coefficientOfVariation,
        boolean highIncomeVolatility,
        long provenMonthlySavingCents,
        int monthsSaved,
        int targetMonthsWindow,
        long instalmentCapCents,
        long requestedAmountCents,
        int requestedTermMonths,
        long suggestedLoanCents,
        int suggestedTermMonths,
        long suggestedMonthlyInstalmentCents,
        String memberDisputeNote,
        List<String> fallbackReasons,
        List<String> fallbackOfficerQuestions
    ) {
    }

    record NarrationResult(
        List<String> plainLanguageReasons,
        List<String> officerQuestions
    ) {
    }

    NarrationResult narrate(NarrationInput input);
}
