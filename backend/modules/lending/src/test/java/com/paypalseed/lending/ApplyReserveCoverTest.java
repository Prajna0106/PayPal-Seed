package com.paypalseed.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.engines.schedule.Allocation;
import com.paypalseed.engines.schedule.Allocator;
import com.paypalseed.engines.schedule.Bucket;
import com.paypalseed.engines.schedule.MoneyEvent;
import com.paypalseed.engines.schedule.RepaymentPolicy;
import com.paypalseed.engines.schedule.Schedule;
import com.paypalseed.engines.schedule.ScheduleGenerator;
import com.paypalseed.lending.api.dto.LendingDtos.CoverOutcome;
import com.paypalseed.lending.api.dto.LendingDtos.CoverResult;
import com.paypalseed.lending.api.dto.LendingDtos.ReserveCover;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.application.usecases.ApplyReserveCover;
import com.paypalseed.lending.application.usecases.LoanRecalculator;
import com.paypalseed.lending.application.usecases.LoanRecalculator.Outcome;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.domain.model.LoanTransaction;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.lending.domain.model.LoanTransaction.Type;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.testing.FakeBusinessClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The reserve cover use case over an in-memory loan: the real allocation engine folds the history, only the storage
 * is faked. The use case has no ledger collaborator at all, which is how "posts nothing" is guaranteed.
 */
class ApplyReserveCoverTest {

    private static final UUID LOAN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID JOURNAL = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final LocalDate DISBURSED = LocalDate.of(2026, 9, 1);
    /** 8 instalments of $150, due on the 15th from 2026-09-15; by 2026-11-20 the first three are due. */
    private static final Schedule SCHEDULE = ScheduleGenerator.generate(120_000, 8, 15, DISBURSED, RepaymentPolicy.V1);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Instant NOW = Instant.parse("2026-11-20T20:00:00Z");

    private final Map<String, LoanTransaction> stored = new HashMap<>();
    private final List<Object> published = new ArrayList<>();
    private LoanStatus status;
    private LoanRepository loans;
    private TransactionRepository transactions;
    private RepaymentService repayments;
    private LoanRecalculator recalculator;
    private ApplyReserveCover cover;

    @BeforeEach
    void setUp() {
        status = LoanStatus.ACTIVE;
        loans = mock(LoanRepository.class);
        transactions = mock(TransactionRepository.class);
        recalculator = mock(LoanRecalculator.class);
        repayments = mock(RepaymentService.class);
        ApplicationEventPublisher events = published::add;

        when(loans.lock(LOAN)).thenAnswer(i -> Optional.of(loan(outstanding())));
        when(loans.find(LOAN)).thenAnswer(i -> Optional.of(loan(outstanding())));
        when(transactions.byPaypalTxnId(any())).thenAnswer(i -> Optional.ofNullable(stored.get(i.<String>getArgument(0))));
        org.mockito.Mockito.doAnswer(i -> {
            LoanTransaction t = i.getArgument(0);
            stored.put(t.paypalTxnId(), t);
            return null;
        }).when(transactions).insert(any());
        when(recalculator.project(any(), any())).thenAnswer(i -> projection(i.<List<MoneyEvent>>getArgument(1)));
        when(recalculator.recalculate(any(), any(), any())).thenAnswer(i -> {
            Allocation after = projection(List.of()).allocation();
            LoanRow row = loan(after.outstandingCents());
            return new Outcome(row, row, SCHEDULE, after, null, List.of());
        });

        cover = new ApplyReserveCover(loans, transactions, recalculator, repayments, new FakeBusinessClock(TODAY), events);
    }

    private long outstanding() {
        return projection(List.of()).allocation().outstandingCents();
    }

    private LoanRecalculator.Projection projection(List<MoneyEvent> extra) {
        List<MoneyEvent> history = new ArrayList<>(stored.values().stream().map(LoanTransaction::toMoneyEvent).toList());
        history.addAll(extra);
        return new LoanRecalculator.Projection(SCHEDULE, Allocator.allocate(SCHEDULE, history, RepaymentPolicy.V1));
    }

    private LoanRow loan(long outstandingCents) {
        LoanStatus shown = status == LoanStatus.ACTIVE && outstandingCents == 0 ? LoanStatus.REPAID : status;
        return new LoanRow(LOAN, MEMBER, 120_000, 8, 15, DISBURSED, RepaymentPolicy.V1.version(), shown, CollectionMode.MANUAL, 1,
            outstandingCents, 0, 0, Bucket.CURRENT, null, null, null, 1);
    }

    private void memberPaid(long cents) {
        stored.put("PAY-" + stored.size(), new LoanTransaction(Ids.newId(), LOAN, Type.RECEIPT, Source.CHECKOUT,
            "PAY-" + stored.size(), null, cents, 0, null, NOW, DISBURSED.plusDays(30), UUID.randomUUID(), "test"));
    }

    private ReserveCover coverOf(long cents) {
        return new ReserveCover(LOAN, cents, "reserve-cover:abc", JOURNAL, TODAY, NOW);
    }

    @Test
    void theMoneyRunsThroughTheWaterfallAndIsRecordedAsAReserveReceiptWithoutPostingToTheLedger() {
        CoverResult result = cover.apply(coverOf(20_000));

        assertThat(result.outcome()).isEqualTo(CoverOutcome.APPLIED);
        assertThat(result.appliedCents()).isEqualTo(20_000);
        assertThat(result.unusedCents()).isZero();
        assertThat(result.outstandingCents()).isEqualTo(100_000);

        LoanTransaction receipt = stored.get("reserve-cover:abc");
        assertThat(receipt.type()).isEqualTo(Type.RECEIPT);
        assertThat(receipt.source()).isEqualTo(Source.RESERVE);
        assertThat(receipt.amountCents()).isEqualTo(20_000);
        assertThat(receipt.feeCents()).isZero();
        assertThat(receipt.journalEntryId()).as("the entry reserve posted, kept so the books can be followed").isEqualTo(JOURNAL);
        assertThat(receipt.effectiveDate()).isEqualTo(TODAY);
        assertThat(receipt.occurredAt()).isEqualTo(NOW);

        // What is due is paid first, in order: the whole first instalment, then the start of the second.
        Allocation allocation = projection(List.of()).allocation();
        assertThat(allocation.of(1).paidCents()).isEqualTo(15_000);
        assertThat(allocation.of(2).paidCents()).isEqualTo(5_000);
        assertThat(allocation.of(3).paidCents()).isZero();

        ArgumentCaptor<LoanRecalculator.Projection> settled = ArgumentCaptor.forClass(LoanRecalculator.Projection.class);
        verify(repayments).cancelRetriesNoLongerNeeded(eq(LOAN), settled.capture());
        assertThat(settled.getValue().allocation().of(1).isFullyPaid()).isTrue();
        verify(recalculator).recalculate(any(), eq(TODAY), eq(LoanRecalculator.Cause.PAYMENT));
    }

    @Test
    void theNormalEventsAnnounceAReserveRepayment() {
        cover.apply(coverOf(20_000));

        assertThat(published).hasSize(2);
        LendingEvents.RepaymentReceived received = (LendingEvents.RepaymentReceived) published.get(0);
        assertThat(received.loanId()).isEqualTo(LOAN);
        assertThat(received.memberId()).isEqualTo(MEMBER);
        assertThat(received.source()).isEqualTo("RESERVE");
        assertThat(received.amountCents()).isEqualTo(20_000);
        assertThat(received.appliedCents()).isEqualTo(20_000);
        assertThat(received.excessCents()).isZero();
        assertThat(received.outstandingCents()).isEqualTo(100_000);
        assertThat(published.get(1)).isInstanceOf(LendingEvents.MandateSyncRequested.class);
    }

    @Test
    void aReplayOfTheSameApprovalChangesNothing() {
        cover.apply(coverOf(20_000));
        published.clear();

        CoverResult replay = cover.apply(coverOf(20_000));

        assertThat(replay.outcome()).isEqualTo(CoverOutcome.ALREADY_APPLIED);
        assertThat(replay.appliedCents()).isEqualTo(20_000);
        assertThat(stored).hasSize(1);
        assertThat(published).isEmpty();
        verify(transactions, times(1)).insert(any());
        verify(recalculator, times(1)).recalculate(any(), any(), any());
        assertThat(projection(List.of()).allocation().outstandingCents()).as("applied once").isEqualTo(100_000);
    }

    @Test
    void aCoverLargerThanWhatIsOwedIsCappedAtWhatIsOwed() {
        memberPaid(115_000);

        CoverResult result = cover.apply(coverOf(20_000));

        assertThat(result.outcome()).isEqualTo(CoverOutcome.APPLIED);
        assertThat(result.appliedCents()).isEqualTo(5_000);
        assertThat(result.unusedCents()).as("the ledger credited all of it; the desk has to see the difference").isEqualTo(15_000);
        assertThat(result.outstandingCents()).isZero();
        assertThat(stored.get("reserve-cover:abc").amountCents()).isEqualTo(5_000);
        Allocation allocation = projection(List.of()).allocation();
        assertThat(allocation.overpaymentCents()).as("the reserve's money never becomes the member's overpayment").isZero();
        LendingEvents.RepaymentReceived received = (LendingEvents.RepaymentReceived) published.get(0);
        assertThat(received.appliedCents()).isEqualTo(5_000);
        assertThat(received.excessCents()).isZero();
        assertThat(received.loanStatus()).isEqualTo("REPAID");
    }

    @Test
    void aLoanThatIsAlreadyPaidTakesNothing() {
        memberPaid(120_000);

        CoverResult result = cover.apply(coverOf(20_000));

        assertThat(result.outcome()).isEqualTo(CoverOutcome.NOTHING_DUE);
        assertThat(result.appliedCents()).isZero();
        assertThat(result.unusedCents()).isEqualTo(20_000);
        assertThat(stored).doesNotContainKey("reserve-cover:abc");
        assertThat(published).isEmpty();
        verify(transactions, never()).insert(argThatIsReserve());
        verify(recalculator, never()).recalculate(any(), any(), any());
    }

    @Test
    void aCancelledLoanTakesNothing() {
        status = LoanStatus.CANCELLED;

        CoverResult result = cover.apply(coverOf(20_000));

        assertThat(result.outcome()).isEqualTo(CoverOutcome.NOTHING_DUE);
        assertThat(stored).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void moneyNeverCountsBeforeTheLoanExisted() {
        cover.apply(new ReserveCover(LOAN, 15_000, "reserve-cover:abc", JOURNAL, DISBURSED.minusDays(3), NOW));

        assertThat(stored.get("reserve-cover:abc").effectiveDate()).isEqualTo(DISBURSED);
    }

    @Test
    void theSameApprovalReferenceForAnotherLoanIsRefused() {
        cover.apply(coverOf(20_000));
        UUID otherLoan = UUID.randomUUID();

        assertThatThrownBy(() -> cover.apply(new ReserveCover(otherLoan, 20_000, "reserve-cover:abc", JOURNAL, TODAY, NOW)))
            .isInstanceOf(DomainException.class)
            .satisfies(e -> assertThat(((DomainException) e).errorCode()).isEqualTo(CommonErrors.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void anUnknownLoanIsNotFound() {
        UUID missing = UUID.randomUUID();
        when(loans.lock(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cover.apply(new ReserveCover(missing, 20_000, "reserve-cover:xyz", JOURNAL, TODAY, NOW)))
            .isInstanceOf(DomainException.class)
            .satisfies(e -> assertThat(((DomainException) e).errorCode()).isEqualTo(LendingErrors.LOAN_NOT_FOUND));
        assertThat(stored).isEmpty();
    }

    @Test
    void aCoverIsAPositiveAmount() {
        assertThatThrownBy(() -> coverOf(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coverOf(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static LoanTransaction argThatIsReserve() {
        return org.mockito.ArgumentMatchers.argThat(t -> t != null && t.source() == Source.RESERVE);
    }
}
