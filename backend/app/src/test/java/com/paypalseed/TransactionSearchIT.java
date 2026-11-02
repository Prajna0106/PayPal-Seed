package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.CounterpartyKind;
import com.paypalseed.payments.api.dto.PaymentsDtos.IncomeCredit;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchedTransaction;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Transaction Search through the facade with the feature switched on: long ranges are cut into windows PayPal
 * accepts, a line on a window edge counts once, and fees, refunds and non-income lines are netted out.
 */
@TestPropertySource(properties = "seed.paypal.transaction-search.enabled=true")
class TransactionSearchIT extends IntegrationTest {

    @Autowired
    PaymentsFacade payments;

    private static SearchedTransaction line(String id, String code, String status, String at, long amount, long fee, String ref,
                                            boolean person) {
        return new SearchedTransaction(id, code, status, Instant.parse(at), amount, fee, Optional.ofNullable(ref),
                Optional.of("PAYER-" + id), person, "Gig " + id);
    }

    @BeforeEach
    void seed() {
        paypal.transactions.clear();
        paypal.transactions.addAll(List.of(
                line("A", "T0006", "S", "2026-08-10T18:00:00Z", 20_000, -590, null, true),
                line("B", "T0006", "S", "2026-09-01T07:00:00Z", 3_000, 0, null, true), // exactly on a window edge
                line("C", "T0006", "S", "2026-09-12T18:00:00Z", 10_000, -320, null, true),
                line("C-REFUND", "T1107", "S", "2026-09-13T18:00:00Z", -4_000, 0, "C", false),
                line("D", "T0001", "S", "2026-10-02T18:00:00Z", 50_000, 0, null, false),
                line("FUNDING", "T0300", "S", "2026-10-05T18:00:00Z", 9_000, 0, null, false),
                line("PENDING", "T0006", "P", "2026-10-06T18:00:00Z", 7_000, 0, null, true)));
    }

    @Test
    void aSeventySixDayRangeIsReadInWindowsOfAtMostThirtyOneDaysAndNettedOnce() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 10, 15);

        List<IncomeCredit> credits = payments.incomeTransactions(null, from, to);

        assertThat(paypal.searches).hasSize(3);
        Instant cursor = from.atStartOfDay(BusinessClock.PROGRAM_ZONE).toInstant();
        for (var search : paypal.searches) {
            assertThat(search.from()).isEqualTo(cursor);
            assertThat(Duration.between(search.from(), search.to())).isLessThanOrEqualTo(Duration.ofDays(31));
            assertThat(search.onBehalfOfPayerId()).isEmpty();
            cursor = search.to();
        }
        assertThat(cursor).isEqualTo(to.plusDays(1).atStartOfDay(BusinessClock.PROGRAM_ZONE).toInstant());

        assertThat(credits).extracting(IncomeCredit::transactionId).containsExactly("A", "B", "C", "D");
        IncomeCredit a = credits.get(0);
        assertThat(a.grossCents()).isEqualTo(20_000);
        assertThat(a.feeCents()).isEqualTo(590);
        assertThat(a.netCents()).isEqualTo(19_410);
        assertThat(a.date()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(a.counterparty()).isEqualTo(CounterpartyKind.PERSON);
        IncomeCredit c = credits.get(2);
        assertThat(c.refundedCents()).isEqualTo(4_000);
        assertThat(c.netCents()).isEqualTo(5_680);
        assertThat(credits.get(3).counterparty()).isEqualTo(CounterpartyKind.PAYOUT);
    }

    @Test
    void anAccountReferenceIsIgnoredUnlessPartnerReadingIsSwitchedOn() {
        payments.incomeTransactions("MEMBER-PAYER-ID", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));

        assertThat(paypal.searches).singleElement().satisfies(s -> assertThat(s.onBehalfOfPayerId()).isEmpty());
    }

    @Test
    void anUnavailableOrRefusingPayPalIsReported() {
        paypal.failNext("searchTransactions", FakePayPal.unavailable());
        assertThatThrownBy(() -> payments.incomeTransactions(null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrors.DEPENDENCY_UNAVAILABLE));

        paypal.failNext("searchTransactions", FakePayPal.refused("NOT_AUTHORIZED"));
        assertThatThrownBy(() -> payments.incomeTransactions(null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(PaymentsErrors.PAYPAL_REJECTED);
                    assertThat(e.details()).containsEntry("paypalIssue", "NOT_AUTHORIZED");
                });
    }

    @Test
    void aBackwardsRangeIsRefused() {
        assertThatThrownBy(() -> payments.incomeTransactions(null, LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
