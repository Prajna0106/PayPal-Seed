package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.HardshipCaps;
import com.paypalseed.engines.rules.hardship.OptionKind;
import com.paypalseed.hardship.api.ReasonClassifier;
import com.paypalseed.hardship.api.events.HardshipEvents;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.domain.model.HardshipErrors;
import com.paypalseed.hardship.domain.model.NarrativeTemplates;
import com.paypalseed.hardship.domain.model.OfferModel.ChoiceRow;
import com.paypalseed.hardship.domain.model.OfferModel.OfferRow;
import com.paypalseed.hardship.domain.model.OfferModel.OptionRow;
import com.paypalseed.hardship.domain.model.OfferStaleException;
import com.paypalseed.hardship.infrastructure.persistence.CaseRecordsRepository;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.hardship.infrastructure.persistence.OfferRepository;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.time.LocalDate;
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
 * The member's side of a case: reading the offer, choosing, declining, asking for help, and the same actions by
 * SMS. A choice is stored consent; nothing changes the schedule until the choice is approved (HINV-1). Another
 * member's loan or case always looks like "no open offer", so ids cannot be probed.
 */
@Service
public class MemberDecisions {

    private static final Logger log = LoggerFactory.getLogger(MemberDecisions.class);

    public record MemberCase(CaseRow c, Optional<OfferRow> offer, List<OptionRow> options, Map<String, String> messages,
                             String messageSource) {
    }

    public record ChoiceResult(UUID caseId, CaseStatus status, String optionLabel, String tier, Instant decisionDueAt,
                               LocalDate holdUntil, Long payNowCents) {
    }

    private final CaseRepository cases;
    private final OfferRepository offers;
    private final CaseRecordsRepository records;
    private final OfferBuilder offerBuilder;
    private final CaseLifecycle lifecycle;
    private final CaseOpening opening;
    private final RiskAssessor assessor;
    private final ApprovalFlow approvals;
    private final OutreachService outreach;
    private final ReasonClassification reasons;
    private final LendingFacade lending;
    private final HardshipSettings settings;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public MemberDecisions(CaseRepository cases, OfferRepository offers, CaseRecordsRepository records,
                           OfferBuilder offerBuilder, CaseLifecycle lifecycle, CaseOpening opening, RiskAssessor assessor,
                           ApprovalFlow approvals, OutreachService outreach, ReasonClassification reasons,
                           LendingFacade lending, HardshipSettings settings, BusinessClock clock,
                           ApplicationEventPublisher events) {
        this.cases = cases;
        this.offers = offers;
        this.records = records;
        this.offerBuilder = offerBuilder;
        this.lifecycle = lifecycle;
        this.opening = opening;
        this.assessor = assessor;
        this.approvals = approvals;
        this.outreach = outreach;
        this.reasons = reasons;
        this.lending = lending;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
    }

    /** The open case and active offer of the member's loan. Opening it counts as having seen the offer. */
    @Transactional
    public MemberCase view(UUID loanId, UUID memberId) {
        requireOwnLoan(loanId, memberId);
        CaseRow c = cases.openForLoan(loanId)
                .orElseThrow(() -> new DomainException(HardshipErrors.NO_OPEN_OFFER, "No open hardship offer"));
        if (c.status() == CaseStatus.DETECTED || c.status() == CaseStatus.OUTREACH_SENT) {
            cases.claimOutreach(c.id(), clock.now());
            cases.setStatus(c.id(), CaseStatus.OFFER_VIEWED);
            records.cancelTimers(c.id(), List.of("AGENT_DEADLINE"));
            c = cases.find(c.id()).orElseThrow();
        }
        return memberCase(c);
    }

    /** The member asks for help in the app. Their words are classified into a closed category and then discarded. */
    @Transactional
    public MemberCase askForHelp(UUID loanId, UUID memberId, String reasonText) {
        requireOwnLoan(loanId, memberId);
        String category = reasonText == null || reasonText.isBlank() ? null : reasons.classify(reasonText, "en").name();
        RiskAssessor.Assessment a = assessor.assess(loanId, clock.today(), true)
                .orElseThrow(() -> new DomainException(HardshipErrors.NOT_ELIGIBLE, "Nothing is owed on this loan"));
        CaseRow c = opening.openForMember(a, category, CaseStatus.OFFER_VIEWED)
                .orElseThrow(() -> new DomainException(HardshipErrors.NOT_ELIGIBLE, "This loan cannot get hardship options now"));
        return memberCase(c);
    }

    @Transactional(noRollbackFor = OfferStaleException.class)
    public ChoiceResult choose(UUID caseId, UUID memberId, UUID offerId, UUID optionId, String channel, String idempotencyKey) {
        CaseRow c = ownCase(caseId, memberId);
        Optional<ChoiceRow> replay = offers.choiceByKey(idempotencyKey);
        if (replay.isPresent()) {
            return result(cases.find(caseId).orElseThrow(), replay.get().optionId());
        }
        if (!c.status().awaitsMember()) {
            throw new DomainException(HardshipErrors.CASE_CLOSED, "This case is " + c.status());
        }
        OfferRow offer = offers.active(caseId).orElseThrow(() -> new DomainException(HardshipErrors.NO_OPEN_OFFER, "No active offer"));
        if (!offer.id().equals(offerId)) {
            if (offers.find(offerId).filter(o -> o.caseId().equals(caseId)).isPresent()) {
                throw new OfferStaleException("A newer offer replaced this one", Map.of("offerId", offer.id().toString()));
            }
            throw new DomainException(HardshipErrors.UNKNOWN_OPTION, "Unknown offer");
        }
        OptionRow option = offers.options(offer.id()).stream().filter(o -> o.id().equals(optionId)).findFirst()
                .orElseThrow(() -> new DomainException(HardshipErrors.UNKNOWN_OPTION, "Unknown option"));
        requireFresh(c, option);
        OptionKind kind = OptionKind.fromLabel(option.label());
        CaseRepository.AppliedCounts counts = cases.appliedCounts(c.loanId(),
                clock.today().minusDays(settings.policy().options().capWindowDays()));
        HardshipCaps.violation(kind, new HardshipCaps.History(counts.aInWindow(), counts.bInWindow(), counts.bTotal(),
                counts.cTotal()), settings.policy()).ifPresent(cap -> {
                    throw new DomainException(HardshipErrors.POLICY_VIOLATION, "Policy cap reached: " + cap);
                });
        if (!offers.insertChoice(new ChoiceRow(Ids.newId(), caseId, offer.id(), option.id(), "CHOOSE", channel, idempotencyKey),
                memberId, NarrativeTemplates.CONSENT_TEXT_VERSION)) {
            throw new DomainException(HardshipErrors.CASE_CLOSED, "A choice was already made on this offer");
        }
        offers.setStatus(offer.id(), "ACCEPTED");
        cases.setChoice(caseId, option.label(), CaseStatus.OPTION_CHOSEN);
        records.cancelTimers(caseId, List.of("AGENT_DEADLINE", "OFFER_REMINDER", "OFFER_EXPIRY"));
        lifecycle.audit("HARDSHIP_OPTION_CHOSEN", c, "MEMBER", "member", Map.of("option", option.label(), "channel", channel,
                "snapshotHash", option.snapshotHash()));
        events.publishEvent(new HardshipEvents.HardshipOptionChosen(Ids.newId(), caseId, c.loanId(), c.memberId(), clock.today(),
                offer.id(), option.label(), option.tier(), option.snapshotHash()));
        ApprovalFlow.ChoiceOutcome outcome = approvals.afterChoice(cases.find(caseId).orElseThrow(), option);
        return new ChoiceResult(caseId, outcome.status(), option.label(), outcome.tier(), outcome.decisionDueAt(),
                outcome.holdUntil(), kind == OptionKind.D_PAYMENT ? option.summary().get("payNowCents") : null);
    }

    /** One tap, no reason needed. Allowed until the option has been approved. */
    @Transactional
    public CaseStatus decline(UUID caseId, UUID memberId, String channel, String idempotencyKey) {
        CaseRow c = ownCase(caseId, memberId);
        if (offers.choiceByKey(idempotencyKey).isPresent() || c.status() == CaseStatus.DECLINED_BY_MEMBER) {
            return CaseStatus.DECLINED_BY_MEMBER;
        }
        if (!c.status().canMoveTo(CaseStatus.DECLINED_BY_MEMBER)) {
            throw new DomainException(HardshipErrors.CASE_CLOSED, "This case is " + c.status());
        }
        offers.latest(caseId).ifPresent(o -> offers.insertChoice(new ChoiceRow(Ids.newId(), caseId, o.id(), null, "DECLINE",
                channel, idempotencyKey), memberId, NarrativeTemplates.CONSENT_TEXT_VERSION));
        lifecycle.close(c, CaseStatus.DECLINED_BY_MEMBER, "MEMBER_DECLINED");
        return CaseStatus.DECLINED_BY_MEMBER;
    }

    /**
     * An SMS reply from a known member, already reduced to a closed keyword. Idempotent per message. Option letters
     * act on the member's newest open case; HELP opens one on {@code fallbackLoanId} when none is open.
     */
    @Transactional(noRollbackFor = OfferStaleException.class)
    public String handleSmsReply(UUID memberId, String keyword, String messageId, UUID fallbackLoanId) {
        Optional<CaseRow> open = cases.latestOpenForMember(memberId);
        if (!records.recordSmsReply(messageId, memberId, keyword, open.map(CaseRow::id).orElse(null), "RECEIVED")) {
            return "DUPLICATE";
        }
        String outcome = smsOutcome(memberId, keyword, messageId, open, fallbackLoanId);
        records.setSmsOutcome(messageId, outcome);
        return outcome;
    }

    private String smsOutcome(UUID memberId, String keyword, String messageId, Optional<CaseRow> open, UUID fallbackLoanId) {
        switch (keyword) {
            case "A", "B", "C", "D" -> {
                if (open.isEmpty()) {
                    return "NO_OPEN_CASE";
                }
                Optional<OfferRow> offer = offers.active(open.get().id());
                Optional<OptionRow> option = offer.flatMap(o -> offers.options(o.id()).stream()
                        .filter(op -> op.label().equals(keyword)).findFirst());
                if (option.isEmpty()) {
                    return "OPTION_NOT_OFFERED";
                }
                try {
                    choose(open.get().id(), memberId, offer.get().id(), option.get().id(), "SMS", "sms:" + messageId);
                    return "CHOSEN_" + keyword;
                } catch (OfferStaleException e) {
                    outreach.resend(open.get().id());
                    return "OFFER_STALE";
                } catch (DomainException e) {
                    log.info("SMS choice {} for case {} refused: {}", keyword, open.get().id(), e.errorCode().code());
                    return "REFUSED_" + e.errorCode().code();
                }
            }
            case "NO" -> {
                if (open.isEmpty()) {
                    return "NO_OPEN_CASE";
                }
                try {
                    decline(open.get().id(), memberId, "SMS", "sms:" + messageId);
                    return "DECLINED";
                } catch (DomainException e) {
                    return "REFUSED_" + e.errorCode().code();
                }
            }
            case "HELP" -> {
                if (open.isPresent()) {
                    outreach.resend(open.get().id());
                    return "OFFER_RESENT";
                }
                if (fallbackLoanId == null) {
                    return "NO_LOAN";
                }
                Optional<RiskAssessor.Assessment> a = assessor.assess(fallbackLoanId, clock.today(), true)
                        .filter(x -> x.facts().memberId().equals(memberId));
                if (a.isEmpty()) {
                    return "NOTHING_OWED";
                }
                Optional<CaseRow> c = opening.openForMember(a.get(), null, CaseStatus.DETECTED);
                if (c.isEmpty()) {
                    return "NOT_ELIGIBLE";
                }
                outreach.issue(c.get().id(), false);
                return "CASE_OPENED";
            }
            default -> {
                return "IGNORED";
            }
        }
    }

    private void requireFresh(CaseRow c, OptionRow option) {
        String current;
        try {
            current = offerBuilder.fresh(c.loanId(), option).snapshotHash();
        } catch (DomainException e) {
            current = null;
        }
        if (!option.snapshotHash().equals(current)) {
            lifecycle.revise(c);
            throw new OfferStaleException("The plan changed since this offer was made; please confirm the new numbers",
                    Map.of("caseId", c.id().toString()));
        }
    }

    private MemberCase memberCase(CaseRow c) {
        Optional<OfferRow> offer = offers.active(c.id()).or(() -> offers.latest(c.id()));
        List<OptionRow> options = offer.map(o -> offers.options(o.id())).orElse(List.of());
        Map<String, String> messages = new java.util.LinkedHashMap<>();
        String source = "TEMPLATE";
        for (String locale : List.of("en", "es")) {
            Optional<CaseRecordsRepository.NarrativeRow> t = records.latestNarrative(c.id(), "MEMBER", locale);
            if (t.isPresent()) {
                messages.put(locale, t.get().text());
                source = "AI".equals(t.get().source()) ? "AI" : source;
            }
        }
        return new MemberCase(c, offer, options, messages, source);
    }

    private ChoiceResult result(CaseRow c, UUID optionId) {
        OptionRow option = optionId == null ? null : offers.option(optionId).orElse(null);
        return new ChoiceResult(c.id(), c.status(), option == null ? null : option.label(), option == null ? null : option.tier(),
                null, null, option != null && "D".equals(option.label()) ? option.summary().get("payNowCents") : null);
    }

    private CaseRow ownCase(UUID caseId, UUID memberId) {
        CaseRow c = cases.lock(caseId).orElseThrow(() -> new DomainException(HardshipErrors.NO_OPEN_OFFER, "No open hardship offer"));
        if (!c.memberId().equals(memberId)) {
            throw new DomainException(HardshipErrors.NO_OPEN_OFFER, "No open hardship offer");
        }
        return c;
    }

    private void requireOwnLoan(UUID loanId, UUID memberId) {
        Optional<LoanSummary> loan = lending.loan(loanId);
        if (loan.isEmpty() || !loan.get().memberId().equals(memberId)) {
            throw new DomainException(HardshipErrors.NO_OPEN_OFFER, "No open hardship offer");
        }
    }
}
