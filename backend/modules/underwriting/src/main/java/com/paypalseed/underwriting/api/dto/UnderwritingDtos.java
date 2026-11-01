package com.paypalseed.underwriting.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class UnderwritingDtos {

    public static final int SUMMARY_WEEKS = 13;

    private UnderwritingDtos() {
    }

    /**
     * {@code weeklyInflowCents} holds 13 consecutive 7-day buckets, oldest first; the last ends the day before the
     * summary date. {@code usualPaydayDow} is the ISO day of week income usually arrives, or null when unknown.
     */
    public record IncomeSummary(List<Long> weeklyInflowCents, Integer usualPaydayDow, int activeWeeks) {
        public IncomeSummary {
            weeklyInflowCents = List.copyOf(weeklyInflowCents);
            if (weeklyInflowCents.size() != SUMMARY_WEEKS) {
                throw new IllegalArgumentException("expected " + SUMMARY_WEEKS + " weekly buckets");
            }
        }

        /** The buckets as the primitive array the hardship engine takes. */
        public long[] weeks() {
            return weeklyInflowCents.stream().mapToLong(Long::longValue).toArray();
        }
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
