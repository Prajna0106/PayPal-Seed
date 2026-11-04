package com.paypalseed.app;

import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.hardship.api.events.HardshipEvents.HardshipApplied;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the credit record (ADR 0014). The record imports no other business module, because lending will read a
 * member's level from it and Spring Modulith rejects a cycle even through events. This wiring module may depend on
 * all of them, so it listens to the savings, lending and hardship events and hands the record plain facts.
 *
 * <p>Only outcomes are forwarded. Hardship contributes the "plan applied" marker and nothing else: no signal, case
 * opening or risk level ever reaches the record (ADR 0011). Every handler is idempotent on the fact key, so the
 * at-least-once delivery of events is safe.
 *
 * <p>Help from the safety fund (ADR 0020) reaches lending as a repayment with source {@code RESERVE}. Each instalment
 * line says how many cents of it the fund paid, so the record learns exactly which instalments were the fund's: they
 * count neither for nor against the member, and a loan the fund paid in full is not one the member completed
 * (ADR 0025). A loan whose payout came back is void and leaves the record entirely.
 */
@Component
class RecordFeed {

    /** A loan that ages 61 or more days past due is in default. */
    private static final String DEFAULT_BUCKET = "DPD_60_PLUS";
    private static final String SLA_BREACH = "SLA_BREACH";
    private static final Set<String> PAID_STATUSES = Set.of("PAID", "PAID_LATE", "PREPAID");

    private final CreditRecordFacade record;
    private final LendingFacade lending;
    private final BusinessClock clock;

    RecordFeed(CreditRecordFacade record, LendingFacade lending, BusinessClock clock) {
        this.record = record;
        this.lending = lending;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(DepositReceivedEvent e) {
        record.ingest(RecordFact.savingsDeposit("saving-deposit:" + e.eventId(), memberOf(e.memberId()),
                clock.businessDateFor(e.timestamp())));
    }

    @ApplicationModuleListener
    void on(LendingEvents.ScheduleGenerated e) {
        record.ingest(RecordFact.loanDisbursed("loan-disbursed:" + e.loanId(), e.memberId(), e.loanId(), e.businessDate()));
    }

    @ApplicationModuleListener
    void on(LendingEvents.RepaymentReceived e) {
        recordPaidInstalments(e.loanId(), e.memberId());
    }

    /**
     * A miss lending attributes to {@code SLA_BREACH} (our own decision on a hardship case ran late, ADR 0011) is not
     * the member's and never reaches the record. {@code MEMBER} and an unattributed miss do. The record keeps no
     * attribution of its own, so excluding the fact here is the whole rule (ADR 0014, ADR 0021).
     */
    @ApplicationModuleListener
    void on(LendingEvents.InstalmentMissed e) {
        if (SLA_BREACH.equals(e.attribution())) {
            return;
        }
        record.ingest(RecordFact.instalmentMissed("inst-missed:" + e.eventId(), e.memberId(), e.loanId(), e.seq(),
                e.businessDate()));
    }

    @ApplicationModuleListener
    void on(LendingEvents.LoanRepaid e) {
        recordPaidInstalments(e.loanId(), e.memberId());
        record.ingest(RecordFact.loanRepaid(e.memberId(), e.loanId(), e.instalmentsMissed(), e.businessDate()));
    }

    @ApplicationModuleListener
    void on(LendingEvents.LateBucketChanged e) {
        // A hardship plan moves the dates, so a member being helped does not age to 61 days; no case needs checking here.
        if (DEFAULT_BUCKET.equals(e.toBucket())) {
            record.ingest(RecordFact.loanDefaulted(e.memberId(), e.loanId(), e.businessDate()));
        }
    }

    @ApplicationModuleListener
    void on(HardshipApplied e) {
        record.ingest(RecordFact.hardshipPlanApplied("hardship-applied:" + e.eventId(), e.memberId(), e.caseId(), e.loanId(),
                e.businessDate()));
    }

    /**
     * The payout that funded the loan came back and the loan is void (ADR 0024). The record drops it whole: no loan, no
     * instalments, no on-plan count, no default and no level it lifted (ADR 0025 follow-up).
     */
    @ApplicationModuleListener
    void on(LendingEvents.LoanVoided e) {
        record.ingest(RecordFact.loanVoided(e.memberId(), e.loanId(), e.businessDate()));
    }

    /**
     * Lending publishes no per-instalment event, so the schedule is the source: every instalment it shows as paid
     * becomes one fact, with the days between its due date and the day it was fully paid. Early payment is 0 days.
     */
    private void recordPaidInstalments(UUID loanId, UUID memberId) {
        for (RecordFact fact : instalmentFacts(memberId, loanId, lending.currentSchedule(loanId).instalments())) {
            record.ingest(fact);
        }
    }

    /**
     * The facts for the instalments a schedule shows as paid, from the exact cents each line says the safety fund paid
     * ({@code reserveCoveredCents}, repayment source {@code RESERVE}, ADR 0020).
     *
     * <ul>
     *   <li>The fund paid the whole instalment: {@code INSTALMENT_FUND_COVERED}, then the paid fact, which then counts
     *       neither on time nor late (ADR 0025).</li>
     *   <li>The fund paid part and the member the rest: the member's own instalment, on time or late by the day the
     *       member finished it. The fund's part is help and is not held against them, and it does not make the
     *       member's payment less theirs; they completed it.</li>
     *   <li>The fund paid nothing: the member's own instalment.</li>
     * </ul>
     * An instalment is only reported once it is fully paid, so one the fund part-paid and the member has not finished
     * is still open (and a miss on it is still the member's to settle).
     */
    static List<RecordFact> instalmentFacts(UUID memberId, UUID loanId, List<InstalmentLine> lines) {
        List<RecordFact> facts = new ArrayList<>();
        for (InstalmentLine line : lines) {
            if (!PAID_STATUSES.contains(line.status()) || line.paidOn() == null) {
                continue;
            }
            LocalDate paidOn = line.paidOn();
            if (fundPaidInFull(line)) {
                facts.add(RecordFact.instalmentFundCovered(memberId, loanId, line.seq(), paidOn));
            }
            int daysLate = (int) Math.max(0, ChronoUnit.DAYS.between(line.dueDate(), paidOn));
            facts.add(RecordFact.instalmentPaid(memberId, loanId, line.seq(), daysLate, paidOn));
        }
        return facts;
    }

    private static boolean fundPaidInFull(InstalmentLine line) {
        return line.reserveCoveredCents() > 0 && line.reserveCoveredCents() >= line.amountCents();
    }

    /** Savings keys members by text; a name that is not a UUID maps to a stable one, as in savings itself. */
    static UUID memberOf(String memberId) {
        try {
            return UUID.fromString(memberId);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(memberId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
