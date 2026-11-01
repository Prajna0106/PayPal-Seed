package com.paypalseed.creditrecord.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the credit record returns. Counts and dates only: no money amounts and nothing that identifies a person. */
public final class CreditRecordDtos {

    private CreditRecordDtos() {
    }

    public enum ShareDetail {
        /** The level only. */
        LEVEL,
        /** Also the month the record began and the loans repaid on plan. */
        SUMMARY,
        /** Also the on-time band. */
        FULL
    }

    public record SavingsCounts(int onTimeMonths, int missedMonths) {
    }

    public record LoanCounts(int disbursed, int repaidOnPlan, int repaidOffPlan) {
    }

    public record InstalmentCounts(int onTime, int late, int openLate) {
    }

    public record NextStepView(String level, int onTimeSavingMonthsToGo, int loansRepaidOnPlanToGo, int requiredOnTimeBps,
                               boolean onTimeShareTooLow, boolean latePaymentOpen) {
    }

    /** {@code loanLimitCents} is the ceiling the level unlocks; {@code ladderTier} is the matching microloan rung. */
    public record RecordView(String level, int levelRank, long loanLimitCents, int ladderTier, LocalDate since,
                             LocalDate levelSince, int onTimeBps, String onTimeBand, SavingsCounts savings, LoanCounts loans,
                             InstalmentCounts instalments, int hardshipPlansOnPlan, int defaults, NextStepView next,
                             String policyVersion) {
    }

    /** What lending needs to size a loan. */
    public record LevelView(String level, int levelRank, long loanLimitCents, int ladderTier) {
    }

    public record HistoryItem(long seq, String kind, LocalDate occurredOn, Integer daysLate) {
    }

    /** {@code nextBefore} is null on the last page. */
    public record HistoryPage(List<HistoryItem> items, Long nextBefore) {
    }

    public record CreateShare(int expiresInDays, ShareDetail detail) {
    }

    /** The token appears here and nowhere else. */
    public record ShareCreated(UUID shareId, String token, String verifyPath, ShareDetail detail, Instant issuedAt,
                               Instant expiresAt) {
    }

    public record ShareView(UUID shareId, ShareDetail detail, String status, Instant issuedAt, Instant expiresAt,
                            Instant revokedAt) {
    }

    /** Fields beyond the level are null unless the member chose a wider detail. */
    public record VerifiedRecord(boolean valid, String level, LocalDate since, Integer loansRepaidOnPlan, String onTimeBand,
                                 Instant issuedAt, Instant expiresAt) {
    }

    public record DeskRecordView(UUID memberId, RecordView record, List<HistoryItem> recent) {
    }

    public enum IngestOutcome {
        RECORDED,
        /** The fact key was already known; nothing changed. */
        DUPLICATE
    }
}
