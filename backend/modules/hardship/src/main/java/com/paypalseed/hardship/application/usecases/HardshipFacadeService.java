package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.GroundingChecker;
import com.paypalseed.hardship.api.HardshipFacade;
import com.paypalseed.hardship.api.dto.HardshipDtos.AgentOutcome;
import com.paypalseed.hardship.api.dto.HardshipDtos.CaseContext;
import com.paypalseed.hardship.api.dto.HardshipDtos.MemberCaseView;
import com.paypalseed.hardship.api.dto.HardshipDtos.OptionView;
import com.paypalseed.hardship.api.dto.HardshipDtos.PriorCaseView;
import com.paypalseed.hardship.api.dto.HardshipDtos.SignalView;
import com.paypalseed.hardship.api.dto.HardshipDtos.SignalsView;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.domain.model.HardshipErrors;
import com.paypalseed.hardship.domain.model.OfferModel.OptionRow;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.hardship.infrastructure.persistence.OfferRepository;
import com.paypalseed.hardship.infrastructure.persistence.ScorecardRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class HardshipFacadeService implements HardshipFacade {

    private final CaseRepository cases;
    private final OfferRepository offers;
    private final ScorecardRepository scorecards;
    private final OutreachService outreach;
    private final MemberDecisions members;
    private final HardshipSettings settings;
    private final BusinessClock clock;

    HardshipFacadeService(CaseRepository cases, OfferRepository offers, ScorecardRepository scorecards, OutreachService outreach,
                          MemberDecisions members, HardshipSettings settings, BusinessClock clock) {
        this.cases = cases;
        this.offers = offers;
        this.scorecards = scorecards;
        this.outreach = outreach;
        this.members = members;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CaseContext> caseContext(UUID caseId) {
        return cases.find(caseId).map(c -> {
            LocalDate scanDate = scorecards.find(c.runId()).map(ScorecardRepository.Run::scanDate).orElse(c.openedOn());
            boolean outreachAllowed = c.origin() != CaseRow.Origin.PROACTIVE_SCAN || c.outreachSentAt() != null
                    || cases.outreachesSince(c.memberId(), clock.now().minus(30, ChronoUnit.DAYS))
                    < settings.policy().outreach().per30Days();
            return new CaseContext(c.id(), c.loanId(), c.memberId(), c.status().name(), c.origin().name(), c.targetSeq(),
                    c.targetDueDate(), c.targetAmountCents(), scanDate, c.riskLevel(), c.points(), c.reasonCategory(),
                    outreachAllowed, !c.status().terminal());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberCaseView> openCaseOf(UUID memberId) {
        return cases.latestOpenForMember(memberId)
                .filter(c -> !(c.origin() == CaseRow.Origin.PROACTIVE_SCAN && c.status() == CaseStatus.DETECTED))
                .map(c -> new MemberCaseView(c.id(), c.loanId(), c.status().name(), c.targetDueDate(), c.targetAmountCents(),
                        c.status() == CaseStatus.OUTREACH_SENT || c.status() == CaseStatus.OFFER_VIEWED));
    }

    @Override
    @Transactional(readOnly = true)
    public SignalsView signals(UUID caseId) {
        CaseRow c = require(caseId);
        List<SignalView> signals = scorecards.signals(c.runId()).stream()
                .map(s -> new SignalView(s.code(), s.points(), s.evidence())).toList();
        return new SignalsView(c.policyVersion(), c.scorecardVersion(), c.riskLevel(), c.points(), signals, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OptionView> options(UUID caseId) {
        CaseRow c = require(caseId);
        return offers.active(c.id()).or(() -> offers.latest(c.id()))
                .map(o -> offers.options(o.id()).stream().map(HardshipFacadeService::view).toList())
                .orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PriorCaseView> priorCases(UUID loanId) {
        return cases.forLoan(loanId).stream()
                .filter(c -> c.status().terminal())
                .map(c -> new PriorCaseView(c.id(), c.status().name(), c.chosenOption(), c.openedOn(), c.closedOn()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GroundingChecker.Allowed allowedFigures(UUID caseId, GroundingChecker.Audience audience) {
        CaseRow c = require(caseId);
        List<OptionRow> options = offers.active(c.id()).or(() -> offers.latest(c.id()))
                .map(o -> offers.options(o.id())).orElse(List.of());
        return AllowedFigures.build(c, options, scorecards.signals(c.runId()), audience, settings.policy());
    }

    @Override
    public void recordAgentOutcome(AgentOutcome outcome) {
        outreach.recordAgentOutcome(outcome);
    }

    @Override
    public void handleSmsReply(UUID memberId, String keyword, String messageId, UUID recentLoanId) {
        members.handleSmsReply(memberId, keyword, messageId, recentLoanId);
    }

    static OptionView view(OptionRow o) {
        return new OptionView(o.label(), o.primitive(), o.args(), o.snapshotHash(), o.tier(), o.recommended(), o.sustainable(),
                o.warnings(), o.summary());
    }

    private CaseRow require(UUID caseId) {
        return cases.find(caseId).orElseThrow(() -> new DomainException(HardshipErrors.CASE_NOT_FOUND, "No case " + caseId));
    }
}
