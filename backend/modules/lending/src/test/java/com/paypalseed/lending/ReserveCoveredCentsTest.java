package com.paypalseed.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paypalseed.engines.schedule.Allocator;
import com.paypalseed.engines.schedule.Bucket;
import com.paypalseed.engines.schedule.RepaymentPolicy;
import com.paypalseed.engines.schedule.Schedule;
import com.paypalseed.engines.schedule.ScheduleGenerator;
import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.application.usecases.LoanQueries;
import com.paypalseed.lending.application.usecases.LoanRecalculator;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.domain.model.LoanTransaction;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.lending.domain.model.LoanTransaction.Type;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.ProjectionRepository;
import com.paypalseed.lending.infrastructure.persistence.ScheduleRepository;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.testing.FakeBusinessClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which cents of each instalment the loss reserve paid, as the schedule view and the repaid event report them. */
class ReserveCoveredCentsTest {

    private static final UUID LOAN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate DISBURSED = LocalDate.of(2026, 9, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Schedule SCHEDULE = ScheduleGenerator.generate(120_000, 8, 15, DISBURSED, RepaymentPolicy.V1);

    private static LoanTransaction receipt(Source source, long cents, int day) {
        return new LoanTransaction(Ids.newId(), LOAN, Type.RECEIPT, source, source + "-" + cents, null, cents, 0, null,
            Instant.parse("2026-11-20T10:00:00Z"), DISBURSED.plusDays(day), UUID.randomUUID(), "test");
    }

    private static LoanQueries queriesOver(List<LoanTransaction> history) {
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.forLoan(LOAN)).thenReturn(history);
        return new LoanQueries(mock(LoanRepository.class), mock(ScheduleRepository.class), transactions,
            mock(ProjectionRepository.class), mock(LoanRecalculator.class), mock(LedgerFacade.class), new FakeBusinessClock(TODAY));
    }

    private static LoanRow loanOwing(long outstandingCents) {
        return new LoanRow(LOAN, MEMBER, 120_000, 8, 15, DISBURSED, RepaymentPolicy.V1.version(), LoanStatus.ACTIVE,
            CollectionMode.MANUAL, 1, outstandingCents, 0, 0, Bucket.CURRENT, DISBURSED, null, null, 1);
    }

    @Test
    void theScheduleViewSplitsEachInstalmentBetweenTheMemberAndTheReserve() {
        List<LoanTransaction> history = List.of(receipt(Source.CHECKOUT, 10_000, 40), receipt(Source.RESERVE, 20_000, 80));
        var allocation = Allocator.allocate(SCHEDULE, history.stream().map(LoanTransaction::toMoneyEvent).toList(), RepaymentPolicy.V1);

        List<InstalmentLine> lines = queriesOver(history).view(loanOwing(allocation.outstandingCents()), SCHEDULE, allocation, TODAY)
            .instalments();

        assertThat(lines.stream().mapToLong(InstalmentLine::reserveCoveredCents).sum()).isEqualTo(20_000);
        assertThat(lines.stream().mapToLong(InstalmentLine::paidCents).sum()).isEqualTo(30_000);
        assertThat(lines).allSatisfy(l -> assertThat(l.reserveCoveredCents()).isLessThanOrEqualTo(l.paidCents()));
        assertThat(lines.stream().mapToLong(l -> l.paidCents() - l.reserveCoveredCents()).sum()).isEqualTo(10_000);
    }

    @Test
    void aLoanTheReservePaidNothingOnReportsZeroEverywhere() {
        LoanTransaction member = receipt(Source.CHECKOUT, 15_000, 40);
        var allocation = Allocator.allocate(SCHEDULE, List.of(member.toMoneyEvent()), RepaymentPolicy.V1);

        assertThat(queriesOver(List.of(member)).view(loanOwing(allocation.outstandingCents()), SCHEDULE, allocation, TODAY)
            .instalments()).allSatisfy(l -> assertThat(l.reserveCoveredCents()).isZero());
    }

    @Test
    void theOldShapesStillWorkAndAnInstalmentCannotClaimMoreThanItReceived() {
        InstalmentLine plain = new InstalmentLine(1, TODAY, 15_000, 15_000, 0, "PAID", TODAY);
        assertThat(plain.reserveCoveredCents()).isZero();
        assertThatThrownBy(() -> new InstalmentLine(1, TODAY, 15_000, 5_000, 10_000, "PARTIAL", null, 5_001))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstalmentLine(1, TODAY, 15_000, 5_000, 10_000, "PARTIAL", null, -1))
            .isInstanceOf(IllegalArgumentException.class);

        LendingEvents.LoanRepaid old = new LendingEvents.LoanRepaid(Ids.newId(), LOAN, MEMBER, TODAY, 8, 0, 0);
        assertThat(old.reserveCoveredCents()).isZero();
        assertThat(new LendingEvents.LoanRepaid(Ids.newId(), LOAN, MEMBER, TODAY, 8, 0, 0, 9_000).reserveCoveredCents())
            .isEqualTo(9_000);
    }
}
