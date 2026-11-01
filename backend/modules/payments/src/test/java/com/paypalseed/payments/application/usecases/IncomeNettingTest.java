package com.paypalseed.payments.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.payments.api.dto.PaymentsDtos.CounterpartyKind;
import com.paypalseed.payments.api.dto.PaymentsDtos.IncomeCredit;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchedTransaction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IncomeNettingTest {

    private static final Instant NOON = Instant.parse("2026-10-05T19:00:00Z");

    private static SearchedTransaction line(String id, String code, String status, long amount, long fee, String ref,
                                            boolean person, Instant at) {
        return new SearchedTransaction(id, code, status, at, amount, fee, Optional.ofNullable(ref),
                Optional.of("ACC-" + id), person, "subject " + id);
    }

    @Test
    void windowsNeverExceedThirtyOneDaysAndCoverTheRangeWithoutGaps() {
        Instant from = Instant.parse("2026-01-01T08:00:00Z");
        Instant to = Instant.parse("2026-04-10T08:00:00Z");

        List<Instant[]> windows = IncomeNetting.windows(from, to);

        assertThat(windows).hasSize(4);
        assertThat(windows.get(0)[0]).isEqualTo(from);
        assertThat(windows.get(windows.size() - 1)[1]).isEqualTo(to);
        for (int i = 0; i < windows.size(); i++) {
            assertThat(java.time.Duration.between(windows.get(i)[0], windows.get(i)[1])).isLessThanOrEqualTo(IncomeNetting.MAX_WINDOW);
            if (i > 0) {
                assertThat(windows.get(i)[0]).isEqualTo(windows.get(i - 1)[1]);
            }
        }
    }

    @Test
    void aShortRangeIsOneWindowAndAnEmptyRangeIsNone() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");

        assertThat(IncomeNetting.windows(from, from.plusSeconds(3600))).hasSize(1);
        assertThat(IncomeNetting.windows(from, from)).isEmpty();
    }

    @Test
    void feesComeOffTheGrossAndTheCreditKeepsItsDate() {
        List<IncomeCredit> credits = IncomeNetting.credits(List.of(line("T1", "T0006", "S", 46_500, -1_379, null, true, NOON)));

        assertThat(credits).singleElement().satisfies(c -> {
            assertThat(c.grossCents()).isEqualTo(46_500);
            assertThat(c.feeCents()).isEqualTo(1_379);
            assertThat(c.refundedCents()).isZero();
            assertThat(c.netCents()).isEqualTo(45_121);
            assertThat(c.date()).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(c.counterparty()).isEqualTo(CounterpartyKind.PERSON);
            assertThat(c.description()).isEqualTo("subject T1");
        });
    }

    @Test
    void aPartialRefundReducesTheNetAndAFullRefundLeavesNothing() {
        List<IncomeCredit> credits = IncomeNetting.credits(List.of(
                line("T1", "T0006", "S", 10_000, -300, null, true, NOON),
                line("R1", "T1107", "S", -2_500, 0, "T1", false, NOON.plusSeconds(60)),
                line("T2", "T0006", "S", 5_000, -175, null, true, NOON),
                line("R2", "T1107", "S", -5_000, 0, "T2", false, NOON.plusSeconds(60))));

        assertThat(credits).extracting(IncomeCredit::transactionId).containsExactly("T1", "T2");
        assertThat(credits.get(0).refundedCents()).isEqualTo(2_500);
        assertThat(credits.get(0).netCents()).isEqualTo(7_200);
        assertThat(credits.get(1).refundedCents()).isEqualTo(5_000);
        assertThat(credits.get(1).netCents()).isZero();
    }

    @Test
    void chargebacksAndReversalsNetLikeRefunds() {
        List<IncomeCredit> credits = IncomeNetting.credits(List.of(
                line("T1", "T0006", "S", 4_000, 0, null, true, NOON),
                line("C1", "T1201", "S", -4_000, 0, "T1", false, NOON)));

        assertThat(credits).singleElement().satisfies(c -> assertThat(c.netCents()).isZero());
    }

    @Test
    void onlySettledPaymentsReceivedCountAsIncome() {
        List<IncomeCredit> credits = IncomeNetting.credits(List.of(
                line("OK", "T0006", "S", 1_000, 0, null, true, NOON),
                line("PENDING", "T0006", "P", 1_000, 0, null, true, NOON),
                line("DENIED", "T0006", "D", 1_000, 0, null, true, NOON),
                line("SPENT", "T0006", "S", -1_000, 0, null, true, NOON),
                line("FUNDING", "T0300", "S", 1_000, 0, null, false, NOON),
                line("WITHDRAWAL", "T0400", "S", -1_000, 0, null, false, NOON),
                line("FEE", "T0100", "S", 1_000, 0, null, false, NOON)));

        assertThat(credits).extracting(IncomeCredit::transactionId).containsExactly("OK");
    }

    @Test
    void aRefundWhoseSaleIsOutsideTheWindowIsIgnoredAndDoesNotCreateIncome() {
        List<IncomeCredit> credits = IncomeNetting.credits(List.of(line("R1", "T1107", "S", -2_500, 0, "OLD", false, NOON)));

        assertThat(credits).isEmpty();
    }

    @Test
    void aLineSeenInTwoWindowsCountsOnceAndResultsAreOldestFirst() {
        SearchedTransaction later = line("B", "T0006", "S", 700, 0, null, true, Instant.parse("2026-10-09T19:00:00Z"));
        SearchedTransaction earlier = line("A", "T0006", "S", 300, 0, null, true, Instant.parse("2026-10-02T19:00:00Z"));

        List<IncomeCredit> credits = IncomeNetting.credits(List.of(later, earlier, later));

        assertThat(credits).extracting(IncomeCredit::transactionId).containsExactly("A", "B");
    }

    @Test
    void counterpartyKindFollowsTheEventCodeThenThePayer() {
        List<IncomeCredit> credits = IncomeNetting.credits(List.of(
                line("PAYOUT", "T0001", "S", 1_000, 0, null, false, NOON),
                line("SUB", "T0002", "S", 1_000, 0, null, false, NOON),
                line("PERSON", "T0000", "S", 1_000, 0, null, true, NOON),
                line("BUSINESS", "T0006", "S", 1_000, 0, null, false, NOON),
                new SearchedTransaction("UNKNOWN", "T0006", "S", NOON, 1_000, 0, Optional.empty(), Optional.empty(), false, "")));

        assertThat(credits).extracting(IncomeCredit::counterparty).containsExactlyInAnyOrder(CounterpartyKind.PAYOUT,
                CounterpartyKind.SUBSCRIPTION, CounterpartyKind.PERSON, CounterpartyKind.BUSINESS, CounterpartyKind.UNKNOWN);
    }
}
