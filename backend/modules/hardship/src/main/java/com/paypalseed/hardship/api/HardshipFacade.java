package com.paypalseed.hardship.api;

import com.paypalseed.engines.rules.hardship.GroundingChecker;
import com.paypalseed.hardship.api.dto.HardshipDtos.AgentOutcome;
import com.paypalseed.hardship.api.dto.HardshipDtos.CaseContext;
import com.paypalseed.hardship.api.dto.HardshipDtos.MemberCaseView;
import com.paypalseed.hardship.api.dto.HardshipDtos.OptionView;
import com.paypalseed.hardship.api.dto.HardshipDtos.PriorCaseView;
import com.paypalseed.hardship.api.dto.HardshipDtos.SignalsView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask of hardship. The agent gateway reads cases through it to serve the
 * Guardian's tools; engagement forwards SMS replies. Nothing here lets a caller change a schedule.
 */
public interface HardshipFacade {

    Optional<CaseContext> caseContext(UUID caseId);

    /**
     * The member's newest case that is not finished, as the member may see it. A case Seed opened from a scan and has
     * not yet contacted the member about is not shown: the member was never told, and says nothing about them yet.
     */
    Optional<MemberCaseView> openCaseOf(UUID memberId);

    /** The scorecard result the case was opened on, with the evidence numbers behind each signal. */
    SignalsView signals(UUID caseId);

    /** The options of the case's active offer, every number computed by the engine. */
    List<OptionView> options(UUID caseId);

    /** Outcomes of earlier cases on the loan. Carries no member text and no officer notes. */
    List<PriorCaseView> priorCases(UUID loanId);

    /** Every figure an AI-written text about this case may quote. Built by code, never by a model. */
    GroundingChecker.Allowed allowedFigures(UUID caseId, GroundingChecker.Audience audience);

    /**
     * The result of a Guardian run, reported by the agent gateway after governance judged the proposal.
     * Idempotent on {@code runId}. A failed or rejected run makes the case fall back to template text.
     */
    void recordAgentOutcome(AgentOutcome outcome);

    /**
     * An SMS reply already matched to a member and to a closed keyword set ({@code A}, {@code B},
     * {@code C}, {@code D}, {@code NO}, {@code HELP}). Idempotent on {@code messageId}.
     */
    default void handleSmsReply(UUID memberId, String keyword, String messageId) {
        handleSmsReply(memberId, keyword, messageId, null);
    }

    /**
     * As above; {@code recentLoanId} (the loan of the member's latest message, may be null) lets {@code HELP} open
     * a case when none is open yet.
     */
    void handleSmsReply(UUID memberId, String keyword, String messageId, UUID recentLoanId);
}
