package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.HardshipPolicy;
import com.paypalseed.engines.rules.hardship.RiskLevel;
import com.paypalseed.engines.rules.hardship.SecondOpinion;
import com.paypalseed.hardship.api.events.HardshipEvents;
import com.paypalseed.hardship.application.usecases.RiskAssessor.Assessment;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseRow.Origin;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.domain.model.OfferModel.OfferRow;
import com.paypalseed.hardship.infrastructure.persistence.CaseRecordsRepository;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.hardship.infrastructure.persistence.OfferRepository;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.RiskFacts;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens a case from a scorecard result (proactive) or from the member asking for help, builds its first offer
 * and template texts, and asks the Guardian for better words without ever waiting for it (02-design §4.1).
 */
@Service
public class CaseOpening {

    public static final String MODE_NARRATIVE = "BRIEF_AND_OUTREACH";
    public static final String MODE_SECOND_OPINION = "SECOND_OPINION_THEN_NARRATIVE";
    public static final String MODE_BRIEF = "BRIEF_ONLY";

    /** Why a proactive case was not opened; logged and counted, never shown to the member. */
    public enum Skip {
        LEVEL_TOO_LOW,
        NOT_ELIGIBLE,
        CASE_OPEN,
        PENDING_MONEY_COVERS,
        COOLDOWN,
        OUTREACH_LIMIT
    }

    public record Opened(Optional<CaseRow> opened, Skip skipped) {
    }

    private final CaseRepository cases;
    private final OfferRepository offers;
    private final CaseRecordsRepository records;
    private final OfferBuilder offerBuilder;
    private final CaseLifecycle lifecycle;
    private final LendingFacade lending;
    private final HardshipSettings settings;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public CaseOpening(CaseRepository cases, OfferRepository offers, CaseRecordsRepository records, OfferBuilder offerBuilder,
                       CaseLifecycle lifecycle, LendingFacade lending, HardshipSettings settings, BusinessClock clock,
                       ApplicationEventPublisher events) {
        this.cases = cases;
        this.offers = offers;
        this.records = records;
        this.offerBuilder = offerBuilder;
        this.lifecycle = lifecycle;
        this.lending = lending;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Opens a proactive case at LIKELY_MISS or above, or at a borderline WATCH that the Guardian may escalate
     * (03 §4). Suppression (03 §3.1) is checked first; a member is never contacted more than the limits allow.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Opened openProactive(Assessment a) {
        HardshipPolicy policy = settings.policy();
        boolean borderlineWatch = a.level() == RiskLevel.WATCH && SecondOpinion.borderline(a.score().points(), policy);
        if (!a.level().atLeast(RiskLevel.LIKELY_MISS) && !borderlineWatch) {
            return new Opened(Optional.empty(), Skip.LEVEL_TOO_LOW);
        }
        RiskFacts f = a.facts();
        LocalDate today = clock.today();
        if (!eligible(f, policy)) {
            return new Opened(Optional.empty(), Skip.NOT_ELIGIBLE);
        }
        if (f.hardshipOpen() || cases.openForLoan(f.loanId()).isPresent()) {
            return new Opened(Optional.empty(), Skip.CASE_OPEN);
        }
        if (f.pendingCents() >= f.targetRemainingCents()) {
            return new Opened(Optional.empty(), Skip.PENDING_MONEY_COVERS);
        }
        if (cases.inCooldown(f.loanId(), today)) {
            return new Opened(Optional.empty(), Skip.COOLDOWN);
        }
        if (cases.outreachesSince(f.memberId(), clock.now().minus(30, ChronoUnit.DAYS)) >= policy.outreach().per30Days()) {
            return new Opened(Optional.empty(), Skip.OUTREACH_LIMIT);
        }
        String mode = borderlineWatch || SecondOpinion.borderline(a.score().points(), policy) ? MODE_SECOND_OPINION : MODE_NARRATIVE;
        return new Opened(open(a, Origin.PROACTIVE_SCAN, null, CaseStatus.DETECTED, mode), null);
    }

    /**
     * The member asked (S7 sets the level to IMMINENT). Cool-down and outreach limits never block a member's own
     * request; an already open case is returned as it is.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CaseRow> openForMember(Assessment a, String reasonCategory, CaseStatus startAs) {
        RiskFacts f = a.facts();
        Optional<CaseRow> open = cases.openForLoan(f.loanId());
        if (open.isPresent()) {
            return open;
        }
        if (!eligible(f, settings.policy())) {
            return Optional.empty();
        }
        return open(a, Origin.MEMBER_HELP, reasonCategory, startAs, MODE_BRIEF);
    }

    private Optional<CaseRow> open(Assessment a, Origin origin, String reasonCategory, CaseStatus startAs, String mode) {
        RiskFacts f = a.facts();
        LocalDate today = clock.today();
        Instant now = clock.now();
        boolean proactive = origin == Origin.PROACTIVE_SCAN;
        String dedupe = "loan:" + f.loanId() + ":v" + f.scheduleVersion() + ":s" + f.targetSeq() + ":" + origin
                + (proactive ? "" : ":" + today);
        CaseRow row = new CaseRow(Ids.newId(), f.loanId(), f.memberId(), origin, startAs, a.run().id(), f.scheduleVersion(),
                f.targetSeq(), f.targetDueDate(), f.targetRemainingCents(), a.level().name(), a.score().points(),
                settings.policy().version(), settings.policy().scorecardVersion(), reasonCategory, dedupe, today, "TEMPLATE",
                proactive ? now.plus(settings.agentDeadline()) : null, null, null, null, null, null, null, null, null);
        OfferBuilder.Built built = offerBuilder.build(row, a.income(), 1, today);
        if (built.options().isEmpty()) {
            return Optional.empty();
        }
        if (!cases.insert(row)) {
            return cases.openForLoan(f.loanId());
        }
        lending.setHardshipOpen(f.loanId(), row.caseRef(), true);
        offers.insert(built.offer(), built.options());
        lifecycle.writeTemplates(row, built.offer());
        planTimers(row, built.offer(), proactive);
        lifecycle.audit("HARDSHIP_CASE_OPENED", row, proactive ? "SYSTEM" : "MEMBER", proactive ? "hardship-scan" : "member",
                Map.of("origin", origin.name(), "level", a.level().name(), "points", a.score().points(),
                        "targetSeq", f.targetSeq(), "policyVersion", settings.policy().version()));
        events.publishEvent(new HardshipEvents.HardshipCaseOpened(Ids.newId(), row.id(), row.loanId(), row.memberId(), today,
                origin.name(), row.targetSeq(), row.riskLevel(), row.points(), row.policyVersion()));
        events.publishEvent(new HardshipEvents.HardshipSignalDetected(Ids.newId(), row.id(), row.loanId(), row.memberId(), today,
                row.riskLevel(), row.points(), a.score().signals().stream()
                .map(s -> new HardshipEvents.SignalCode(s.code(), s.points())).collect(Collectors.toList())));
        events.publishEvent(new HardshipEvents.GuardianRunRequested(Ids.newId(), row.id(), row.loanId(), mode));
        return Optional.of(row);
    }

    private void planTimers(CaseRow c, OfferRow offer, boolean proactive) {
        HardshipPolicy policy = settings.policy();
        Instant now = clock.now();
        if (proactive) {
            records.schedule(c.id(), "AGENT_DEADLINE", c.agentDeadlineAt());
        }
        Instant reminder = c.targetDueDate().minusDays(1).atTime(LocalTime.of(10, 0)).atZone(policy.timing().zone()).toInstant();
        if (reminder.isAfter(now)) {
            records.schedule(c.id(), "OFFER_REMINDER", reminder);
        }
        records.schedule(c.id(), "OFFER_EXPIRY", offer.expiresOn().plusDays(1).atStartOfDay(policy.timing().zone()).toInstant());
    }

    static boolean eligible(RiskFacts f, HardshipPolicy policy) {
        return "ACTIVE".equals(f.status()) && !policy.options().ineligibleBucket().equals(f.bucket());
    }
}
