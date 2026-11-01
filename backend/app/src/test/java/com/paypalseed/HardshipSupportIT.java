package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePreview;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePrimitive;
import com.paypalseed.lending.api.dto.LendingDtos.RescheduleRequest;
import com.paypalseed.lending.api.dto.LendingDtos.RiskFacts;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.api.dto.LendingDtos.Slot;
import com.paypalseed.lending.application.usecases.CollectionFailureService;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository.Status;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F5 support in lending (02-design §1.1): idempotent and preview-checked reschedules, the open-case flag, grace
 * holds and the read side the hardship scan uses. F4 behaviour without a flag or a hold is covered by the F4 ITs.
 */
class HardshipSupportIT extends IntegrationTest {

    private static final LocalDate DISBURSED = LocalDate.of(2026, 11, 10); // due day 9: first due 2026-12-09, miss date 12-15

    @Autowired
    ReminderRepository reminders;
    @Autowired
    CollectionFailureService failures;
    @Autowired
    LoanRepository loanRows;
    @Autowired
    AttemptRepository attempts;
    @Autowired
    PlatformTransactionManager txManager;

    private void inTx(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> work.run());
    }

    private void closeThrough(LocalDate date) throws Exception {
        clock.set(date);
        mvc.perform(post("/internal/jobs/cob/catch-up").param("to", date.toString()).header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk());
        settle();
    }

    private record Miss(LocalDate missedOn, String attribution) {
    }

    private List<Miss> misses(UUID loanId) {
        return jdbc.sql("SELECT missed_on, attribution FROM lending.instalment_missed WHERE loan_id = :id ORDER BY seq")
                .param("id", loanId)
                .query((rs, n) -> new Miss(rs.getObject("missed_on", LocalDate.class), rs.getString("attribution")))
                .list();
    }

    private static List<Slot> proposedSlots(ReschedulePreview preview) {
        return preview.proposed().instalments().stream().map(l -> new Slot(l.seq(), l.dueDate(), l.amountCents())).toList();
    }

    private static String code(Throwable t) {
        return ((DomainException) t).errorCode().code();
    }

    /** TC-HEX-02, TC-HEX-16: a replayed approval returns the version it created and publishes nothing new. */
    @Test
    void replayedApprovalReturnsTheSameVersion() {
        LoanSummary loan = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
        clock.set(LocalDate.of(2026, 11, 20));
        RescheduleRequest pause = new RescheduleRequest(loan.loanId(), ReschedulePrimitive.DEFER_TO_END, 1, null, null);
        List<Slot> approved = proposedSlots(lending.previewReschedule(pause));

        ScheduleView first = lending.applyReschedule(pause, "case-1:B:decision-1", approved);
        settle();
        long changes = jdbc.sql("SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%ScheduleChanged'")
                .query(Long.class).single();
        ScheduleView replay = lending.applyReschedule(pause, "case-1:B:decision-1", approved);
        settle();

        assertThat(first.version()).isEqualTo(2);
        assertThat(replay.version()).isEqualTo(2);
        assertThat(replay.instalments()).isEqualTo(first.instalments());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.schedule_version WHERE loan_id = :id").param("id", loan.loanId())
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%ScheduleChanged'")
                .query(Long.class).single()).isEqualTo(changes);
        assertIntegrity(loan.loanId());
    }

    /** HINV-14: money that lands between approval and apply changes the proposal, so apply refuses. */
    @Test
    void applyRefusesWhenTheProposalChangedSinceApproval() throws Exception {
        LoanSummary loan = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
        clock.set(LocalDate.of(2026, 12, 5)); // inside the 7-day due window: a payment goes to instalment 1
        RescheduleRequest pause = new RescheduleRequest(loan.loanId(), ReschedulePrimitive.DEFER_TO_END, 1, null, null);
        List<Slot> approved = proposedSlots(lending.previewReschedule(pause));

        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-PART", loan.loanId(), 5_000));
        settle();

        assertThatThrownBy(() -> lending.applyReschedule(pause, "case-2:B:decision-1", approved))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("reschedule-preview-changed"));
        assertThat(lending.loan(loan.loanId()).orElseThrow().currentVersion()).isEqualTo(1);

        // the fresh preview applies
        ScheduleView applied = lending.applyReschedule(pause, "case-2:B:decision-2", proposedSlots(lending.previewReschedule(pause)));
        assertThat(applied.version()).isEqualTo(2);
        settle();
        assertIntegrity(loan.loanId());
    }

    /**
     * 03 §9: a hold keeps InstalmentMissed unwritten while a human decides (days past due still move), and the
     * first close of business after it ends writes the miss with an attribution. Runs every COB scenario in one
     * method because close of business moves one shared business date forward.
     */
    @Test
    void holdsDelayTheMissAndAnOpenCaseSilencesTheNudge() throws Exception {
        LoanSummary held = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
        LoanSummary escalated = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
        LoanSummary released = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
        LoanSummary plain = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
        LoanSummary flagged = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
        for (LoanSummary l : List.of(plain, flagged)) {
            reminders.upsertContact(l.memberId(), "+14155550123", "en", "America/Los_Angeles");
        }
        closeThrough(DISBURSED);
        inTx(() -> lending.setHardshipOpen(flagged.loanId(), "case-flagged", true));

        // day 3 of grace: the plain loan is nudged, the loan with an open case is not
        closeThrough(LocalDate.of(2026, 12, 12));
        assertThat(reminders.forLoan(plain.loanId())).extracting(r -> r.type()).contains("GRACE_NUDGE");
        assertThat(reminders.forLoan(flagged.loanId())).extracting(r -> r.type()).doesNotContain("GRACE_NUDGE");

        closeThrough(LocalDate.of(2026, 12, 13));
        inTx(() -> {
            lending.placeHold(held.loanId(), 1, LocalDate.of(2026, 12, 17), "case-held", "CHOSEN_OPTION_PENDING");
            lending.placeHold(escalated.loanId(), 1, LocalDate.of(2026, 12, 16), "case-esc", "CHOSEN_OPTION_PENDING");
            lending.placeHold(released.loanId(), 1, LocalDate.of(2026, 12, 17), "case-rel", "CHOSEN_OPTION_PENDING");
            lending.placeHold(held.loanId(), 1, LocalDate.of(2026, 12, 17), "case-held", "CHOSEN_OPTION_PENDING"); // replay
        });
        assertThatThrownBy(() -> inTx(() -> lending.placeHold(held.loanId(), 1, LocalDate.of(2026, 12, 17), "case-other",
                "CHOSEN_OPTION_PENDING"))).satisfies(e -> assertThat(code(e)).isEqualTo("hold-conflict"));
        assertThatThrownBy(() -> inTx(() -> lending.placeHold(held.loanId(), 1, LocalDate.of(2026, 12, 20), "case-held",
                "CHOSEN_OPTION_PENDING"))).satisfies(e -> assertThat(code(e)).isEqualTo("hold-out-of-range"));

        // the miss date: only the loan without a hold records the miss; held loans still age
        closeThrough(LocalDate.of(2026, 12, 15));
        assertThat(misses(plain.loanId())).containsExactly(new Miss(LocalDate.of(2026, 12, 15), null));
        assertThat(misses(held.loanId())).isEmpty();
        assertThat(misses(escalated.loanId())).isEmpty();
        assertThat(misses(released.loanId())).isEmpty();
        assertThat(queries.loan(held.loanId()).dpd()).isEqualTo(6);
        assertThat(queries.loan(held.loanId()).bucket().name()).isEqualTo("DPD_1_7");

        // the officer rejects one case; the SLA breached on another, so its hold is extended once
        inTx(() -> {
            lending.releaseHold("case-rel", "REJECTED_BY_OFFICER");
            lending.releaseHold("case-rel", "REJECTED_BY_OFFICER");
            lending.placeHold(escalated.loanId(), 1, LocalDate.of(2026, 12, 18), "case-esc", "SLA_ESCALATION");
        });
        closeThrough(LocalDate.of(2026, 12, 16));
        assertThat(misses(released.loanId())).containsExactly(new Miss(LocalDate.of(2026, 12, 16), "MEMBER"));
        assertThat(misses(held.loanId())).isEmpty();

        closeThrough(LocalDate.of(2026, 12, 17)); // the last date the hold covers
        assertThat(misses(held.loanId())).isEmpty();
        closeThrough(LocalDate.of(2026, 12, 18));
        assertThat(misses(held.loanId())).containsExactly(new Miss(LocalDate.of(2026, 12, 18), "MEMBER"));
        assertThat(misses(escalated.loanId())).isEmpty();
        closeThrough(LocalDate.of(2026, 12, 19));
        assertThat(misses(escalated.loanId())).containsExactly(new Miss(LocalDate.of(2026, 12, 19), "SLA_BREACH"));

        // closing the case clears the flag; another case's ref does not
        inTx(() -> lending.setHardshipOpen(flagged.loanId(), "case-someone-else", false));
        assertThat(lending.riskFacts(flagged.loanId(), clock.today()).orElseThrow().hardshipOpen()).isTrue();
        inTx(() -> lending.setHardshipOpen(flagged.loanId(), "case-flagged", false));
        assertThat(lending.riskFacts(flagged.loanId(), clock.today()).orElseThrow().hardshipOpen()).isFalse();
        for (LoanSummary l : List.of(held, escalated, released, plain, flagged)) {
            assertIntegrity(l.loanId());
        }
    }

    /** F4 AC3: no automatic retry is planned while a case is open, and opening one cancels a planned retry. */
    @Test
    void openCaseStopsAutomaticRetries() {
        LoanSummary planned = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
        LoanSummary flagged = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
        clock.set(LocalDate.of(2026, 12, 9));
        try {
            inTx(() -> {
                loanRows.setCollectionMode(planned.loanId(), CollectionMode.AUTOPAY);
                loanRows.setCollectionMode(flagged.loanId(), CollectionMode.AUTOPAY);
                lending.setHardshipOpen(flagged.loanId(), "case-f", true);
            });
            failures.recordFailure(planned.loanId(), false, "INSUFFICIENT_FUNDS", "evt-planned");
            failures.recordFailure(flagged.loanId(), false, "INSUFFICIENT_FUNDS", "evt-flagged");
            assertThat(attempts.planned(planned.loanId())).hasSize(1);
            assertThat(attempts.planned(flagged.loanId())).isEmpty();

            inTx(() -> lending.setHardshipOpen(planned.loanId(), "case-p", true));
            assertThat(attempts.planned(planned.loanId())).isEmpty();
            assertThat(attempts.open(planned.loanId())).isEmpty();
            assertThat(jdbc.sql("SELECT reason FROM lending.collection_attempt WHERE loan_id = :id AND status = :s")
                    .param("id", planned.loanId()).param("s", Status.CANCELLED.name()).query(String.class).list())
                    .containsExactly("hardship case open");

            RiskFacts facts = lending.riskFacts(flagged.loanId(), LocalDate.of(2026, 12, 10)).orElseThrow();
            assertThat(facts.failedChargesLast14Days()).isEqualTo(1);
            assertThat(facts.failedTwiceOnTarget()).isFalse();
            assertThat(facts.hardshipOpen()).isTrue();
        } finally {
            inTx(() -> {
                loanRows.setCollectionMode(planned.loanId(), CollectionMode.MANUAL);
                loanRows.setCollectionMode(flagged.loanId(), CollectionMode.MANUAL);
                lending.setHardshipOpen(planned.loanId(), "case-p", false);
                lending.setHardshipOpen(flagged.loanId(), "case-f", false);
            });
        }
    }

    @Test
    void riskFactsDescribeTheOldestOwedInstalment() throws Exception {
        LoanSummary loan = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);
        clock.set(LocalDate.of(2026, 12, 6));
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-SOME", loan.loanId(), 10_000));
        settle();

        RiskFacts facts = lending.riskFacts(loan.loanId(), LocalDate.of(2026, 12, 11)).orElseThrow();
        assertThat(facts.memberId()).isEqualTo(loan.memberId());
        assertThat(facts.status()).isEqualTo("ACTIVE");
        assertThat(facts.scheduleVersion()).isEqualTo(1);
        assertThat(facts.targetSeq()).isEqualTo(1);
        assertThat(facts.targetDueDate()).isEqualTo(LocalDate.of(2026, 12, 9));
        assertThat(facts.targetAmountCents()).isEqualTo(15_000);
        assertThat(facts.targetRemainingCents()).isEqualTo(5_000);
        assertThat(facts.dpd()).isEqualTo(2);
        assertThat(facts.bucket()).isEqualTo("DPD_1_7");
        assertThat(facts.outstandingCents()).isEqualTo(110_000);
        assertThat(facts.owed()).hasSize(8);
        assertThat(facts.owed().get(0).remainingCents()).isEqualTo(5_000);
        assertThat(facts.failedChargesLast14Days()).isZero();
        assertThat(facts.hardshipOpen()).isFalse();
        assertThat(lending.riskFacts(UUID.randomUUID(), LocalDate.of(2026, 12, 11))).isEmpty();
    }

    /** The scan window: oldest owed instalment due in [asOf - grace, asOf + lead], keyset paged by loan id. */
    @Test
    void scanCandidatesFollowTheWindowAndTheCursor() {
        LoanSummary dueSoon = disburse(UUID.randomUUID(), 1_200, 8, 9, DISBURSED);   // first due 2026-12-09
        LoanSummary dueLater = disburse(UUID.randomUUID(), 1_200, 8, 25, DISBURSED); // first due 2026-11-25 or 12-25
        LocalDate asOf = LocalDate.of(2026, 12, 1);
        List<UUID> page = lending.hardshipScanCandidates(asOf, 10, 5, 0, 1, null, 10_000);
        assertThat(page).contains(dueSoon.loanId());
        LocalDate laterDue = lending.currentSchedule(dueLater.loanId()).instalments().get(0).dueDate();
        boolean inWindow = !laterDue.isBefore(asOf.minusDays(5)) && !laterDue.isAfter(asOf.plusDays(10));
        assertThat(page.contains(dueLater.loanId())).isEqualTo(inWindow);

        // keyset paging: the page after the first id is the rest of the same ordering
        assertThat(lending.hardshipScanCandidates(asOf, 10, 5, 0, 1, page.get(0), 10_000))
                .isEqualTo(page.subList(1, page.size()));
        assertThat(lending.hardshipScanCandidates(asOf, 10, 5, 0, 1, null, 1)).containsExactly(page.get(0));
        assertThat(lending.hardshipScanCandidates(LocalDate.of(2026, 11, 20), 10, 5, 0, 1, null, 10_000))
                .doesNotContain(dueSoon.loanId());
    }
}
