package com.paypalseed.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.api.dto.RecordFact.Kind;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecordFeedTest {

    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID LOAN = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 12, 15);
    private static final long AMOUNT = 2_500;

    /** Instalment {@code seq}: fully paid on {@code paidOn}, of which the fund paid {@code reserveCents}. */
    private static InstalmentLine paid(int seq, String status, LocalDate paidOn, long reserveCents) {
        return new InstalmentLine(seq, FIRST_DUE.plusMonths(seq - 1), AMOUNT, AMOUNT, 0, status, paidOn, reserveCents);
    }

    private static InstalmentLine open(int seq, long paidCents, long reserveCents) {
        return new InstalmentLine(seq, FIRST_DUE.plusMonths(seq - 1), AMOUNT, paidCents, AMOUNT - paidCents, "OPEN", null, reserveCents);
    }

    private static List<RecordFact> facts(InstalmentLine... lines) {
        return RecordFeed.instalmentFacts(MEMBER, LOAN, List.of(lines));
    }

    @Test
    void anInstalmentTheFundPaidWholeIsFundCoveredOnTheDayItWasPaidAndStillReportedPaid() {
        List<RecordFact> facts = facts(paid(1, "PAID", FIRST_DUE.plusDays(40), AMOUNT));

        assertThat(facts).extracting(RecordFact::kind).containsExactly(Kind.INSTALMENT_FUND_COVERED, Kind.INSTALMENT_PAID);
        assertThat(facts).extracting(RecordFact::seq).containsOnly(1);
        assertThat(facts).extracting(RecordFact::occurredOn).containsOnly(FIRST_DUE.plusDays(40));
    }

    @Test
    void anInstalmentTheFundPartPaidAndTheMemberFinishedIsTheMembersOwnOnTheDayTheyFinished() {
        List<RecordFact> onTime = facts(paid(1, "PAID", FIRST_DUE.plusDays(3), 1_000));
        List<RecordFact> late = facts(paid(1, "PAID_LATE", FIRST_DUE.plusDays(9), 1_000));

        assertThat(onTime).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(Kind.INSTALMENT_PAID);
            assertThat(f.daysLate()).isEqualTo(3);
        });
        assertThat(late).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(Kind.INSTALMENT_PAID);
            assertThat(f.daysLate()).as("late by the member's own payment date, not the fund's").isEqualTo(9);
            assertThat(f.occurredOn()).isEqualTo(FIRST_DUE.plusDays(9));
        });
    }

    @Test
    void anInstalmentTheFundPaidNothingOnIsTheMembersAndEarlyIsZeroDaysLate() {
        List<RecordFact> facts = facts(paid(2, "PREPAID", FIRST_DUE.plusMonths(1).minusDays(6), 0));

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(Kind.INSTALMENT_PAID);
            assertThat(f.daysLate()).isZero();
        });
    }

    @Test
    void onlyInstalmentsThatAreFullyPaidAreReportedWhoeverPaidPartOfThem() {
        // The fund part-paid instalment 3 and the member has not finished it: it is still open, and no fund fact is made.
        List<RecordFact> facts = facts(paid(1, "PAID", FIRST_DUE, AMOUNT), paid(2, "PAID", FIRST_DUE.plusMonths(1), 0), open(3, 1_000, 1_000),
                open(4, 0, 0));

        assertThat(facts).extracting(RecordFact::kind)
                .containsExactly(Kind.INSTALMENT_FUND_COVERED, Kind.INSTALMENT_PAID, Kind.INSTALMENT_PAID);
        assertThat(facts).extracting(RecordFact::seq).containsExactly(1, 1, 2);
    }

    @Test
    void theFundIsNotCreditedWithAnInstalmentTheMemberCompletedOnTheSameDay() {
        // The old estimate took everything completed on the day the fund paid; the exact amount does not.
        LocalDate day = FIRST_DUE.plusDays(40);
        List<RecordFact> facts = facts(paid(1, "PAID_LATE", day, AMOUNT), paid(2, "PAID_LATE", day, 0));

        assertThat(facts).filteredOn(f -> f.kind() == Kind.INSTALMENT_FUND_COVERED).extracting(RecordFact::seq).containsExactly(1);
    }

    @Test
    void aVoidedLoanIsHandedToTheRecordAsOneFactKeyedByTheLoan() {
        CreditRecordFacade record = mock(CreditRecordFacade.class);
        LendingFacade lending = mock(LendingFacade.class);
        RecordFeed feed = new RecordFeed(record, lending, mock(BusinessClock.class));
        LocalDate day = LocalDate.of(2026, 12, 20);

        feed.on(new LendingEvents.LoanVoided(UUID.randomUUID(), LOAN, MEMBER, day, UUID.randomUUID(), "PAYOUT-1", "RETURNED", 10_000, 0, 0));

        ArgumentCaptor<RecordFact> fact = ArgumentCaptor.forClass(RecordFact.class);
        verify(record).ingest(fact.capture());
        assertThat(fact.getValue().kind()).isEqualTo(Kind.LOAN_VOIDED);
        assertThat(fact.getValue().factKey()).isEqualTo("loan-void:" + LOAN);
        assertThat(fact.getValue().memberId()).isEqualTo(MEMBER);
        assertThat(fact.getValue().loanId()).isEqualTo(LOAN);
        assertThat(fact.getValue().occurredOn()).isEqualTo(day);
        verifyNoInteractions(lending);
    }

    @Test
    void aSlaBreachMissStillNeverReachesTheRecord() {
        CreditRecordFacade record = mock(CreditRecordFacade.class);
        RecordFeed feed = new RecordFeed(record, mock(LendingFacade.class), mock(BusinessClock.class));

        feed.on(new LendingEvents.InstalmentMissed(UUID.randomUUID(), LOAN, MEMBER, FIRST_DUE, 1, 1, FIRST_DUE, "SLA_BREACH"));

        verify(record, org.mockito.Mockito.never()).ingest(any());
    }
}
