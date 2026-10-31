package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.RubberStampDetector;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.ApprovalView;
import com.paypalseed.hardship.api.dto.HardshipDtos.OptionView;
import com.paypalseed.hardship.api.dto.HardshipDtos.SignalView;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.HardshipErrors;
import com.paypalseed.hardship.domain.model.OfferModel.ChoiceRow;
import com.paypalseed.hardship.domain.model.OfferModel.OfferRow;
import com.paypalseed.hardship.infrastructure.persistence.CaseRecordsRepository;
import com.paypalseed.hardship.infrastructure.persistence.CaseRecordsRepository.NarrativeRow;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.hardship.infrastructure.persistence.OfferRepository;
import com.paypalseed.hardship.infrastructure.persistence.ScorecardRepository;
import com.paypalseed.platform.kernel.DomainException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The officer desk (02-design §6.2): a keyset-paged queue filtered only by allowlisted columns, and the evidence
 * panel an officer must see before deciding. Narratives are shown with their source, so an officer always knows
 * which words a model wrote.
 */
@Service
public class DeskQueries {

    public static final Set<String> FILTER_COLUMNS = Set.of("status", "level", "origin");

    public record QueueRow(UUID caseId, UUID loanId, String status, String level, int points, String origin, int targetSeq,
                           java.time.LocalDate targetDueDate, long targetAmountCents, String chosenOption,
                           java.time.LocalDate openedOn) {
    }

    public record Queue(List<QueueRow> rows, UUID nextBefore) {
    }

    public record CaseDetail(QueueRow summary, String policyVersion, List<SignalView> signals, Integer offerRevision,
                             String recommended, List<OptionView> options, String chosenOption, String choiceChannel,
                             List<NarrativeRow> narratives, Optional<ApprovalView> approval, UUID lastAgentRunId,
                             boolean rubberStampFlag, String closedReason) {
    }

    private final CaseRepository cases;
    private final OfferRepository offers;
    private final CaseRecordsRepository records;
    private final ScorecardRepository scorecards;
    private final GovernanceFacade governance;

    public DeskQueries(CaseRepository cases, OfferRepository offers, CaseRecordsRepository records,
                       ScorecardRepository scorecards, GovernanceFacade governance) {
        this.cases = cases;
        this.offers = offers;
        this.records = records;
        this.scorecards = scorecards;
        this.governance = governance;
    }

    @Transactional(readOnly = true)
    public Queue queue(List<String> statuses, List<String> levels, List<String> origins, UUID before, int limit) {
        requireKnown(statuses, Arrays.stream(com.paypalseed.hardship.domain.model.CaseStatus.values()).map(Enum::name).toList());
        requireKnown(levels, List.of("WATCH", "LIKELY_MISS", "IMMINENT"));
        requireKnown(origins, Arrays.stream(CaseRow.Origin.values()).map(Enum::name).toList());
        int size = Math.max(1, Math.min(limit, 200));
        List<QueueRow> rows = cases.queue(statuses, levels, origins, before, size).stream().map(DeskQueries::row).toList();
        return new Queue(rows, rows.size() == size ? rows.get(rows.size() - 1).caseId() : null);
    }

    @Transactional(readOnly = true)
    public CaseDetail detail(UUID caseId, UUID viewerStaffId) {
        CaseRow c = cases.find(caseId).orElseThrow(() -> new DomainException(HardshipErrors.CASE_NOT_FOUND, "No case " + caseId));
        Optional<OfferRow> offer = offers.active(caseId).or(() -> offers.latest(caseId));
        List<OptionView> options = offer.map(o -> offers.options(o.id()).stream().map(HardshipFacadeService::view).toList())
                .orElse(List.of());
        Optional<ChoiceRow> choice = offers.latestChoice(caseId);
        List<SignalView> signals = scorecards.signals(c.runId()).stream()
                .map(s -> new SignalView(s.code(), s.points(), s.evidence())).toList();
        Optional<ApprovalView> approval = c.approvalRequestId() == null ? Optional.empty() : governance.request(c.approvalRequestId());
        boolean flagged = viewerStaffId != null && RubberStampDetector.flag(governance.recentDecisions(viewerStaffId, 20).stream()
                .map(d -> new RubberStampDetector.Decision(d.approve(), d.decidedMs(), d.evidenceViewedMs())).toList());
        return new CaseDetail(row(c), c.policyVersion(), signals, offer.map(OfferRow::revision).orElse(null),
                offer.map(OfferRow::recommended).orElse(null), options, c.chosenOption(), choice.map(ChoiceRow::channel).orElse(null),
                records.narratives(caseId), approval, records.lastAgentRun(caseId).orElse(null), flagged, c.closedReason());
    }

    private static void requireKnown(List<String> values, List<String> allowed) {
        Set<String> unknown = values.stream().filter(v -> !allowed.contains(v)).collect(Collectors.toSet());
        if (!unknown.isEmpty()) {
            throw new DomainException(HardshipErrors.INVALID_FILTER, "Unknown filter values " + unknown);
        }
    }

    private static QueueRow row(CaseRow c) {
        return new QueueRow(c.id(), c.loanId(), c.status().name(), c.riskLevel(), c.points(), c.origin().name(), c.targetSeq(),
                c.targetDueDate(), c.targetAmountCents(), c.chosenOption(), c.openedOn());
    }
}
