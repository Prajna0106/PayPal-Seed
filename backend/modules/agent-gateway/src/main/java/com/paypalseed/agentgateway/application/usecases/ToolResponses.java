package com.paypalseed.agentgateway.application.usecases;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response shapes of the read tools ({@code agent-tools.yaml}). Numbers, enums and dates only. */
public final class ToolResponses {

    private ToolResponses() {
    }

    public record LoanSummaryTool(UUID loanId, String memberRef, long principalCents, long outstandingCents, String status,
                                  String bucket, int dpd, int instalmentCount, int paidInstalments, String collectionMode,
                                  LocalDate businessDate) {
    }

    public record InstalmentRow(int seq, LocalDate dueDate, long amountCents, long paidCents, long remainingCents) {
    }

    public record ScheduleTool(UUID loanId, int version, String reason, LocalDate businessDate,
                               List<InstalmentRow> instalments) {
    }

    public record NextInstalment(int seq, LocalDate dueDate, long amountCents, long remainingCents, long daysUntilDue) {
    }

    public record AgingTool(UUID loanId, String bucket, int dpd, NextInstalment nextInstalment, LocalDate graceEndsOn,
                            int missedInstalments, LocalDate businessDate) {
    }

    public record SignalRow(String code, int points, Map<String, Long> evidence) {
    }

    public record SignalsTool(UUID loanId, String policyVersion, String computedLevel, int points, List<SignalRow> signals,
                              boolean suppressed) {
    }

    public record IncomeSummaryTool(UUID loanId, int windowDays, long medianWeeklyInflowCents, long last4WeeksAverageCents,
                                    String usualPaydayWeekday, List<Long> weeklyInflowCents) {
    }

    /** {@code summary} values are cents, days or counts; keys ending in {@code Date} hold ISO dates. */
    public record OptionPreview(String option, String label, String primitive, String previewHash, boolean eligible,
                                String approvalTier, Map<String, Integer> args, Map<String, Object> summary,
                                Boolean sustainable, List<String> warnings, boolean recommended) {
    }

    public record OfferedOptionsTool(UUID loanId, List<OptionPreview> options) {
    }

    public record PriorCase(UUID caseId, LocalDate openedOn, LocalDate closedOn, String chosenOption, String outcome) {
    }

    public record PriorCasesTool(UUID loanId, List<PriorCase> cases) {
    }
}
