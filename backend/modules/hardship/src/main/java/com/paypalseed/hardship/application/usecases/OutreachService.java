package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.RiskLevel;
import com.paypalseed.engines.rules.hardship.Signal;
import com.paypalseed.hardship.api.dto.HardshipDtos.AgentOutcome;
import com.paypalseed.hardship.api.events.HardshipEvents;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.domain.model.OfferModel.OfferRow;
import com.paypalseed.hardship.infrastructure.persistence.CaseRecordsRepository;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.hardship.infrastructure.persistence.OfferRepository;
import com.paypalseed.hardship.infrastructure.persistence.ScorecardRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gets the offer to the member exactly once (02-design §4.3), with the Guardian's words when governance judged
 * them sound, and the template words otherwise. The Guardian is an enhancement: a failed, rejected, slow or
 * switched-off agent changes only the wording, never whether or when a member is helped.
 */
@Service
public class OutreachService {

    private static final Logger log = LoggerFactory.getLogger(OutreachService.class);

    private final CaseRepository cases;
    private final OfferRepository offers;
    private final CaseRecordsRepository records;
    private final ScorecardRepository scorecards;
    private final CaseLifecycle lifecycle;
    private final HardshipSettings settings;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public OutreachService(CaseRepository cases, OfferRepository offers, CaseRecordsRepository records,
                           ScorecardRepository scorecards, CaseLifecycle lifecycle, HardshipSettings settings,
                           BusinessClock clock, ApplicationEventPublisher events) {
        this.cases = cases;
        this.offers = offers;
        this.records = records;
        this.scorecards = scorecards;
        this.lifecycle = lifecycle;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Publishes the offer for engagement to deliver. The first outreach is claimed atomically, so the agent outcome
     * and the agent deadline racing each other still send one message. A reminder is likewise sent at most once.
     */
    @Transactional
    public boolean issue(UUID caseId, boolean reminder) {
        CaseRow c = cases.lock(caseId).orElseThrow();
        if (c.status().terminal()) {
            return false;
        }
        Instant now = clock.now();
        if (reminder) {
            if ((c.status() != CaseStatus.OUTREACH_SENT && c.status() != CaseStatus.OFFER_VIEWED) || !cases.claimReminder(caseId, now)) {
                return false;
            }
        } else {
            if (!cases.claimOutreach(caseId, now)) {
                return false;
            }
            if (c.status() == CaseStatus.DETECTED) {
                cases.setStatus(caseId, CaseStatus.OUTREACH_SENT);
            }
            records.cancelTimers(caseId, List.of("AGENT_DEADLINE"));
        }
        publishOffer(cases.find(caseId).orElseThrow(), reminder);
        return true;
    }

    /** Re-sends the current offer revision, for example after it went stale or the member texted HELP again. */
    @Transactional
    public void resend(UUID caseId) {
        cases.find(caseId).filter(c -> !c.status().terminal()).ifPresent(c -> publishOffer(c, true));
    }

    private void publishOffer(CaseRow c, boolean reminder) {
        Optional<OfferRow> offer = offers.active(c.id());
        if (offer.isEmpty()) {
            return;
        }
        Map<String, String> messages = new LinkedHashMap<>();
        String source = "TEMPLATE";
        for (String locale : List.of("en", "es")) {
            Optional<CaseRecordsRepository.NarrativeRow> text = records.latestNarrative(c.id(), "MEMBER", locale);
            text.ifPresent(t -> messages.put(locale, t.text()));
            if (text.isPresent() && "AI".equals(text.get().source())) {
                source = "AI";
            }
        }
        events.publishEvent(new HardshipEvents.HardshipOfferIssued(Ids.newId(), c.id(), c.loanId(), c.memberId(), clock.today(),
                offer.get().id(), offer.get().revision(), offer.get().expiresOn(), lifecycle.offered(offers.options(offer.get().id())),
                messages, source, c.origin() == CaseRow.Origin.PROACTIVE_SCAN, reminder));
    }

    /**
     * The Guardian's run, as judged by governance. Idempotent on the run id. In shadow mode the texts are kept for
     * comparison and the templates still go out.
     */
    @Transactional
    public void recordAgentOutcome(AgentOutcome o) {
        CaseRow c = cases.lock(o.caseId()).orElse(null);
        if (c == null || !records.recordAgentOutcome(o.runId(), o.caseId(), o.proposalId(), o.approvable(), o.shadow(),
                o.failedChecks() == null ? List.of() : o.failedChecks(), o.errorCode())) {
            return;
        }
        if (o.approvable()) {
            storeTexts(c, o);
            if (!o.shadow()) {
                escalate(c, o);
            }
        } else {
            log.info("Guardian run {} for case {} not used ({}, checks {})", o.runId(), c.id(), o.errorCode(), o.failedChecks());
        }
        afterAgent(cases.find(c.id()).orElseThrow());
    }

    /** The Guardian did not answer in time: the deterministic path goes ahead on its own (HINV: AI off still works). */
    @Transactional
    public void agentDeadlinePassed(UUID caseId) {
        cases.find(caseId).filter(c -> c.status() == CaseStatus.DETECTED).ifPresent(this::afterAgent);
    }

    /**
     * A borderline WATCH case exists only to let the Guardian escalate it. Without an accepted escalation it closes
     * quietly: the deterministic level stays the floor and no member is contacted at WATCH.
     */
    private void afterAgent(CaseRow c) {
        if (c.status() != CaseStatus.DETECTED || c.outreachSentAt() != null) {
            return;
        }
        if (RiskLevel.valueOf(c.riskLevel()).atLeast(RiskLevel.LIKELY_MISS)) {
            issue(c.id(), false);
        } else {
            lifecycle.close(c, CaseStatus.EXPIRED, "NOT_ESCALATED");
        }
    }

    private void storeTexts(CaseRow c, AgentOutcome o) {
        String source = o.shadow() ? "AI_SHADOW" : "AI";
        boolean member = false;
        if (o.memberMessages() != null) {
            for (Map.Entry<String, String> m : o.memberMessages().entrySet()) {
                if (("en".equals(m.getKey()) || "es".equals(m.getKey())) && m.getValue() != null && !m.getValue().isBlank()) {
                    records.addNarrative(c.id(), "MEMBER", m.getKey(), m.getValue(), source, "PASSED", o.runId());
                    member = true;
                }
            }
        }
        if (o.officerBrief() != null && !o.officerBrief().isBlank()) {
            records.addNarrative(c.id(), "OFFICER", "en", o.officerBrief(), source, "PASSED", o.runId());
        }
        if (member && !o.shadow() && c.outreachSentAt() == null) {
            cases.setNarrativeSource(c.id(), "AI");
        }
    }

    /** The validator accepted an escalate-only opinion; it is stored as its own signal with the run id (03 §4). */
    private void escalate(CaseRow c, AgentOutcome o) {
        if (o.proposedLevel() == null) {
            return;
        }
        RiskLevel current = RiskLevel.valueOf(c.riskLevel());
        RiskLevel proposed = RiskLevel.valueOf(o.proposedLevel());
        if (proposed.ordinal() != current.ordinal() + 1) {
            return;
        }
        int points = proposed.threshold(settings.policy().scoring()) - c.points();
        Map<String, Long> evidence = new LinkedHashMap<>();
        evidence.put("fromPoints", (long) c.points());
        evidence.put("citedSignals", (long) (o.citedSignals() == null ? 0 : o.citedSignals().size()));
        scorecards.insertSignals(c.runId(), List.of(new Signal(Signal.AI_ESCALATION, Math.max(points, 0), evidence)),
                "AI_SECOND_OPINION", o.runId());
        cases.setRisk(c.id(), proposed.name(), c.points() + Math.max(points, 0));
        lifecycle.audit("HARDSHIP_AI_ESCALATION", c, "AGENT", "run:" + o.runId(),
                Map.of("from", current.name(), "to", proposed.name()));
    }
}
