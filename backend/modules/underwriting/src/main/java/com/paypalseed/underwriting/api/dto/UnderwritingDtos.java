package com.paypalseed.underwriting.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class UnderwritingDtos {

    private UnderwritingDtos() {
    }

    public record IncomeMonthDto(String yearMonth, long amountCents) {
    }

    public record ConsentStatusView(
        UUID memberId,
        boolean consented,
        Instant updatedAt,
        List<String> usedData,
        List<String> neverUsedData
    ) {
    }

    public record FairCheckCardView(
        UUID assessmentId,
        UUID memberId,
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
        String inputsHash,
        String modelVersion,
        List<String> plainLanguageReasons,
        List<String> officerQuestions,
        List<String> usedData,
        List<String> neverUsedData,
        boolean memberDisputed,
        String memberDisputeNote,
        String officerDecision,
        UUID officerId,
        long decidedLoanCents,
        int decidedTermMonths,
        String officerReason,
        Instant createdAt,
        Instant decidedAt
    ) {
    }
}
