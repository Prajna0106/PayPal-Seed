package com.paypalseed.hardship.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HardshipDtos {

    private HardshipDtos() {
    }

    /** Agent runs are bound to one case; this is everything a run needs to know about it. */
    public record CaseContext(UUID caseId, UUID loanId, UUID memberId, String status, String origin, int targetSeq,
                              LocalDate targetDueDate, long targetAmountCents, LocalDate scanDate, String riskLevel,
                              int points, String reasonCategory, boolean outreachAllowed, boolean acceptsProposal) {
    }

    public record SignalView(String code, int points, Map<String, Long> evidence) {
    }

    public record SignalsView(String policyVersion, String scorecardVersion, String computedLevel, int points,
                              List<SignalView> signals, boolean suppressed) {
    }

    /**
     * One option of the active offer. {@code summary} holds numbers only (cents, days, counts); dates are
     * epoch days under keys ending in {@code Date}.
     */
    public record OptionView(String label, String primitive, Map<String, Integer> args, String snapshotHash, String tier,
                             boolean recommended, Boolean sustainable, List<String> warnings, Map<String, Long> summary) {
    }

    public record PriorCaseView(UUID caseId, String outcome, String chosenOption, LocalDate openedOn, LocalDate closedOn) {
    }

    /**
     * What the agent gateway reports after governance judged a Guardian proposal. {@code memberMessages} is
     * keyed by locale ({@code en}, {@code es}). In shadow mode the texts are stored for comparison and the
     * templates are sent.
     */
    public record AgentOutcome(UUID caseId, UUID runId, UUID proposalId, boolean approvable, boolean shadow,
                               String proposedLevel, List<String> citedSignals, Map<String, String> memberMessages,
                               String officerBrief, List<String> failedChecks, String errorCode) {
    }
}
