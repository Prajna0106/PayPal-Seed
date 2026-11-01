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
     * Lending publishes no per-instalment event, so the schedule is the source: every instalment it shows as paid
     * becomes one fact, with the days between its due date and the day it was fully paid. Early payment is 0 days.
     */
    private void recordPaidInstalments(UUID loanId, UUID memberId) {
        for (InstalmentLine line : lending.currentSchedule(loanId).instalments()) {
            if (PAID_STATUSES.contains(line.status()) && line.paidOn() != null) {
                int daysLate = (int) Math.max(0, ChronoUnit.DAYS.between(line.dueDate(), line.paidOn()));
                LocalDate paidOn = line.paidOn();
                record.ingest(RecordFact.instalmentPaid(memberId, loanId, line.seq(), daysLate, paidOn));
            }
        }
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
