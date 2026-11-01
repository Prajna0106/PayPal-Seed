package com.paypalseed.hardship.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.engines.rules.hardship.FeatureExtractor;
import com.paypalseed.engines.rules.hardship.IncomeFeatures;
import com.paypalseed.engines.rules.hardship.Signal;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.hardship.api.events.HardshipEvents;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseRow.Origin;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.domain.model.NarrativeTemplates;
import com.paypalseed.hardship.domain.model.OfferModel.OfferRow;
import com.paypalseed.hardship.domain.model.OfferModel.OptionRow;
import com.paypalseed.hardship.infrastructure.persistence.CaseRecordsRepository;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.hardship.infrastructure.persistence.OfferRepository;
import com.paypalseed.hardship.infrastructure.persistence.ScorecardRepository;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared steps every path through a case uses: closing it cleanly (flags, holds, timers, approval request),
 * re-issuing a stale offer, writing the template texts, and the audit trail. All of it joins the caller's
 * transaction, so a case change and its side effects commit together.
 */
@Component
public class CaseLifecycle {

    static final List<String> ALL_TIMERS = List.of("AGENT_DEADLINE", "OFFER_REMINDER", "OFFER_EXPIRY", "HOLD_CHECK", "SLA_50",
            "SLA_80", "SLA_BREACH", "HOLD_EXPIRY", "SAGA_RETRY");

    private final CaseRepository cases;
    private final OfferRepository offers;
    private final CaseRecordsRepository records;
    private final ScorecardRepository scorecards;
    private final OfferBuilder offerBuilder;
    private final LendingFacade lending;
    private final UnderwritingFacade underwriting;
    private final GovernanceFacade governance;
    private final HardshipSettings settings;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    public CaseLifecycle(CaseRepository cases, OfferRepository offers, CaseRecordsRepository records,
                         ScorecardRepository scorecards, OfferBuilder offerBuilder, LendingFacade lending,
                         UnderwritingFacade underwriting, GovernanceFacade governance, HardshipSettings settings,
                         BusinessClock clock, ApplicationEventPublisher events, ObjectMapper json) {
        this.cases = cases;
        this.offers = offers;
        this.records = records;
        this.scorecards = scorecards;
        this.offerBuilder = offerBuilder;
        this.lending = lending;
        this.underwriting = underwriting;
        this.governance = governance;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
        this.json = json;
    }

    /** Ends a case: releases its hold and open-case flag, stops its timers and withdraws an open approval. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void close(CaseRow c, CaseStatus status, String reason) {
        c.status().requireNext(status);
        LocalDate today = clock.today();
        LocalDate cooldown = c.origin() == Origin.PROACTIVE_SCAN
                ? today.plusDays(settings.policy().outreach().cooldownDays()) : null;
        cases.close(c.id(), status, today, reason, cooldown);
        lending.releaseHold(c.caseRef(), status.name());
        lending.setHardshipOpen(c.loanId(), c.caseRef(), false);
        records.cancelTimers(c.id(), ALL_TIMERS);
        offers.active(c.id()).ifPresent(o -> offers.setStatus(o.id(), status == CaseStatus.APPLIED ? "ACCEPTED" : "EXPIRED"));
        if (c.approvalRequestId() != null && status != CaseStatus.APPLIED && status != CaseStatus.REJECTED_BY_OFFICER) {
            governance.voidRequest(c.approvalRequestId(), "case " + status);
        }
        audit("HARDSHIP_CASE_CLOSED", c, "SYSTEM", "hardship", Map.of("status", status.name(), "reason", reason));
        events.publishEvent(new HardshipEvents.HardshipCaseClosed(Ids.newId(), c.id(), c.loanId(), c.memberId(), today,
                status.name()));
    }

    /**
     * The schedule or what is owed changed since the member saw the offer: the old offer becomes STALE, any choice
     * and approval are void, and a new revision built from fresh previews waits for the member (HINV-14).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OfferRow revise(CaseRow c) {
        LocalDate today = clock.today();
        Optional<OfferRow> current = offers.latest(c.id());
        current.filter(o -> "ACTIVE".equals(o.status()) || "ACCEPTED".equals(o.status()))
                .ifPresent(o -> offers.setStatus(o.id(), "STALE"));
        if (c.approvalRequestId() != null) {
            governance.voidRequest(c.approvalRequestId(), "offer stale");
        }
        lending.releaseHold(c.caseRef(), "OFFER_STALE");
        records.cancelTimers(c.id(), List.of("HOLD_CHECK", "SLA_50", "SLA_80", "SLA_BREACH", "HOLD_EXPIRY", "SAGA_RETRY"));
        records.deleteSaga(c.id());
        CaseRow fresh = cases.find(c.id()).orElseThrow();
        OfferBuilder.Built built = offerBuilder.build(fresh, income(fresh, today), current.map(o -> o.revision() + 1).orElse(1),
                today);
        offers.insert(built.offer(), built.options());
        cases.resetChoice(c.id(), CaseStatus.OFFER_VIEWED, built.offer().baseVersion());
        audit("HARDSHIP_OFFER_REVISED", c, "SYSTEM", "hardship", Map.of("revision", built.offer().revision()));
        return built.offer();
    }

    /** The consented income features for the case's target instalment, or empty without consent or history. */
    public Optional<IncomeFeatures> income(CaseRow c, LocalDate asOf) {
        return underwriting.incomeSummary(c.memberId(), asOf)
                .flatMap(s -> FeatureExtractor.extract(s.weeks(), c.targetAmountCents(), settings.policy()));
    }

    /** Template texts for both audiences, so a case always has something safe to send and show. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeTemplates(CaseRow c, OfferRow offer) {
        boolean asked = c.origin() == Origin.MEMBER_HELP;
        for (String locale : List.of("en", "es")) {
            records.addNarrative(c.id(), "MEMBER", locale,
                    NarrativeTemplates.memberParagraph(locale, c.targetAmountCents(), c.targetDueDate(), asked),
                    "TEMPLATE", "NOT_RUN", null);
        }
        List<Signal> signals = scorecards.signals(c.runId()).stream()
                .map(s -> new Signal(s.code(), s.points(), s.evidence())).toList();
        records.addNarrative(c.id(), "OFFICER", "en", NarrativeTemplates.officerBrief(c.riskLevel(), c.points(), signals,
                c.targetSeq(), c.targetAmountCents(), c.targetDueDate(), offer.recommended(), c.reasonCategory()),
                "TEMPLATE", "NOT_RUN", null);
    }

    public List<HardshipEvents.OfferedOption> offered(List<OptionRow> options) {
        return options.stream().map(o -> new HardshipEvents.OfferedOption(o.label(), o.primitive(), o.recommended(),
                o.summary())).toList();
    }

    /** Appends to governance's hash-chained audit log. The payload carries ids, codes and numbers only. */
    public void audit(String entryType, CaseRow c, String actorType, String actorId, Map<String, Object> payload) {
        try {
            governance.audit(new AuditCommand(entryType, c.id(), actorType, actorId, json.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise an audit payload", e);
        }
    }
}
