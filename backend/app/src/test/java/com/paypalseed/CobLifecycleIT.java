package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.engagement.application.usecases.ReminderService;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.application.usecases.CloseOfBusiness;
import com.paypalseed.lending.infrastructure.persistence.ProjectionRepository;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * FR-10, FR-12, FR-18: one loan through reminders, grace, lateness, a missed instalment and a cure,
 * driven by close of business over consecutive business dates.
 */
class CobLifecycleIT extends IntegrationTest {

    @Autowired
    CloseOfBusiness cob;
    @Autowired
    ProjectionRepository projections;
    @Autowired
    ReminderRepository reminders;
    @Autowired
    ReminderService reminderService;

    private void closeThrough(LocalDate date) throws Exception {
        clock.set(date);
        mvc.perform(post("/internal/jobs/cob/catch-up").param("to", date.toString()).header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk());
        settle();
    }

    private List<String> reminderTypes(UUID loanId, String channel) {
        return reminders.forLoan(loanId).stream().filter(r -> r.channel().equals(channel)).map(r -> r.type()).toList();
    }

    @Test
    void anaMissesAPaymentThenCatchesUp() throws Exception {
        LocalDate disbursed = LocalDate.of(2026, 11, 10);
        LoanSummary loan = disburse(UUID.randomUUID(), 1_200, 8, 9, disbursed);
        reminders.upsertContact(loan.memberId(), "+14155550123", "en", "America/Los_Angeles");
        closeThrough(disbursed);

        // T-3: one "payment is due" reminder, in the app and queued for SMS
        closeThrough(LocalDate.of(2026, 12, 6));
        assertThat(reminderTypes(loan.loanId(), "IN_APP")).containsExactly("DUE_SOON");
        assertThat(reminderTypes(loan.loanId(), "SMS")).containsExactly("DUE_SOON");

        // the day after the due date: in grace, bucket 1-7
        closeThrough(LocalDate.of(2026, 12, 10));
        var inGrace = queries.loan(loan.loanId());
        assertThat(inGrace.bucket().name()).isEqualTo("DPD_1_7");
        assertThat(inGrace.dpd()).isEqualTo(1);

        // day 3 of grace: the catch-up nudge
        closeThrough(LocalDate.of(2026, 12, 12));
        assertThat(reminderTypes(loan.loanId(), "IN_APP")).containsExactly("DUE_SOON", "GRACE_NUDGE");

        // day 6: grace has run out; the miss is recorded once as a fact
        closeThrough(LocalDate.of(2026, 12, 15));
        assertThat(projections.missedCount(loan.loanId())).isEqualTo(1);

        // TC-COB-08: closing the same date again changes nothing
        long eventsBefore = jdbc.sql("SELECT COUNT(*) FROM event_publication").query(Long.class).single();
        cob.start(LocalDate.of(2026, 12, 15), "again");
        assertThat(cob.processShard(LocalDate.of(2026, 12, 15), 0, 100).processed()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM event_publication").query(Long.class).single()).isEqualTo(eventsBefore);

        // TC-COB-01: dates are closed strictly in order, never in the future
        clock.set(LocalDate.of(2026, 12, 20));
        mvc.perform(post("/internal/jobs/cob/2026-12-20/start").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cob-out-of-order"));
        mvc.perform(post("/internal/jobs/cob/2027-01-01/start").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("cob-in-the-future"));

        // TC-AGE-05: catching up cures the loan immediately, without waiting for close of business
        closeThrough(LocalDate.of(2026, 12, 16));
        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-CATCHUP", loan.loanId(), 15_000));
        settle();
        var cured = queries.loan(loan.loanId());
        assertThat(cured.bucket().name()).isEqualTo("CURRENT");
        assertThat(projections.transitions(loan.loanId())).extracting(t -> t.fromBucket() + ">" + t.toBucket() + "@" + t.cause())
                .containsExactly("CURRENT>DPD_1_7@COB", "DPD_1_7>CURRENT@PAYMENT");
        assertThat(projections.missedCount(loan.loanId())).isEqualTo(1); // INV-9: facts are never retracted

        // every date from disbursement to today closed exactly once, in order
        List<LocalDate> runs = jdbc.sql("SELECT business_date FROM lending.cob_run ORDER BY business_date").query(LocalDate.class).list();
        assertThat(runs).hasSize(37).first().isEqualTo(disbursed);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.cob_run WHERE status <> 'COMPLETED'").query(Integer.class).single()).isZero();

        // SMS go out through Zapier (dry-run here) and are marked sent
        clock.set(LocalDate.of(2026, 12, 17));
        assertThat(reminderService.dispatchDue(10)).isEqualTo(2);
        assertIntegrity(loan.loanId());
    }
}
