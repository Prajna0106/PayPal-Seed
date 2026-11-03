package com.paypalseed.lending.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.lending.api.events.LendingEvents;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The waiting between refund attempts and the references that make a refund idempotent (ADR 0024, closing pass). */
class HeldRefundsBackoffTest {

    @Test
    void theWaitDoublesAfterEveryFailedAttemptAndNeverPassesADay() {
        Duration base = Duration.ofMinutes(15);

        assertThat(HeldRefunds.backoff(base, 1)).isEqualTo(Duration.ofMinutes(15));
        assertThat(HeldRefunds.backoff(base, 2)).isEqualTo(Duration.ofMinutes(30));
        assertThat(HeldRefunds.backoff(base, 3)).isEqualTo(Duration.ofMinutes(60));
        assertThat(HeldRefunds.backoff(base, 8)).isEqualTo(Duration.ofHours(24));
        assertThat(HeldRefunds.backoff(base, 500)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aZeroDelayStaysZero() {
        assertThat(HeldRefunds.backoff(Duration.ZERO, 5)).isEqualTo(Duration.ZERO);
    }

    @Test
    void aVoidedLoanAndACancelledLoanHaveTheirOwnReferenceAndALatePaymentIsKeyedByItself() {
        UUID loan = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 11, 10);
        LendingEvents.LoanVoided voided = new LendingEvents.LoanVoided(UUID.randomUUID(), loan, UUID.randomUUID(), today, app,
                "ref", "RETURNED", 120_000, 30_000, 0);
        LendingEvents.LoanVoided cancelled = new LendingEvents.LoanVoided(UUID.randomUUID(), loan, UUID.randomUUID(), today, app,
                "ref", LendingEvents.LoanVoided.COOLING_OFF_CANCELLED, 120_000, 30_000, 0);
        UUID payment = UUID.randomUUID();

        assertThat(HeldRefunds.referenceOf(voided)).isEqualTo("void-refund:" + loan);
        assertThat(HeldRefunds.referenceOf(cancelled)).isEqualTo("cancel-refund:" + loan);
        assertThat(HeldRefunds.latePaymentReference(payment)).isEqualTo("void-refund:" + payment);
    }
}
