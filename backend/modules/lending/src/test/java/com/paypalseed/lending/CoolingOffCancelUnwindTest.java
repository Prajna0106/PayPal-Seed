package com.paypalseed.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.engines.rules.LoanTier;
import com.paypalseed.engines.schedule.Allocator;
import com.paypalseed.engines.schedule.Bucket;
import com.paypalseed.engines.schedule.RepaymentPolicy;
import com.paypalseed.engines.schedule.Schedule;
import com.paypalseed.engines.schedule.ScheduleGenerator;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.ledger.api.dto.Side;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.application.usecases.DisbursementReturn;
import com.paypalseed.lending.application.usecases.HeldRefunds;
import com.paypalseed.lending.application.usecases.LoanRecalculator;
import com.paypalseed.lending.domain.model.LoanApplication;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.domain.model.LoanTransaction;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.lending.domain.model.LoanTransaction.Type;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.TimerRepository;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.testing.FakeBusinessClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What the cooling-off cancel posts (ADR 0024, closing pass): the same settlement as a voided payout, so a member who had
 * paid in before cancelling never pushes the receivable below zero, and what they paid is owed back to them as a refund
 * that is written down in the same transaction.
 */
class CoolingOffCancelUnwindTest {

    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate DISBURSED = LocalDate.of(2026, 11, 10);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Schedule SCHEDULE = ScheduleGenerator.generate(120_000, 8, 15, DISBURSED, RepaymentPolicy.V1);

    private final List<LoanTransaction> history = new ArrayList<>();
    private final List<Object> published = new ArrayList<>();
    private LoanRow loanRow;
    private LoanRepository loans;
    private LedgerFacade ledger;
    private HeldRefunds heldRefunds;
    private DisbursementReturn unwinder;

    @BeforeEach
    void setUp() {
        loans = mock(LoanRepository.class);
        TransactionRepository transactions = mock(TransactionRepository.class);
        LoanRecalculator recalculator = mock(LoanRecalculator.class);
        ledger = mock(LedgerFacade.class);
        heldRefunds = mock(HeldRefunds.class);
        ApplicationEventPublisher events = published::add;
        loanRow = row(LoanStatus.ACTIVE, 120_000, 0);

        when(loans.lock(any())).thenAnswer(i -> Optional.of(loanRow));
        when(transactions.forLoan(any())).thenAnswer(i -> List.copyOf(history));
        when(recalculator.project(any(), any())).thenAnswer(i -> new LoanRecalculator.Projection(SCHEDULE,
            Allocator.allocate(SCHEDULE, history.stream().map(LoanTransaction::toMoneyEvent).toList(), RepaymentPolicy.V1)));
        unwinder = new DisbursementReturn(loans, transactions, mock(TimerRepository.class), mock(AttemptRepository.class),
            recalculator, ledger, mock(GovernanceFacade.class), new FakeBusinessClock(TODAY), events, heldRefunds);
    }

    private static LoanRow row(LoanStatus status, long outstanding, long overpaid) {
        return new LoanRow(UUID.fromString("22222222-2222-2222-2222-222222222222"), MEMBER, 120_000, 8, 15, DISBURSED,
            RepaymentPolicy.V1.version(), status, CollectionMode.MANUAL, 1, outstanding, overpaid, 0, Bucket.CURRENT, DISBURSED,
            null, null, 1);
    }

    private LoanApplication disbursedApplication() {
        LoanApplication app = LoanApplication.submit(MEMBER, "Mixer", 120_000, 8, 15, WalletType.PAYPAL, true, LoanTier.LEVEL_1,
            15_000, 15_000, List.of(), NOW);
        app.approve(UUID.randomUUID(), NOW);
        app.approve(UUID.randomUUID(), NOW);
        app.startDisbursement(null, NOW);
        app.markDisbursed(loanRow.id(), app.expectedDisbursementBatchId(), NOW);
        return app;
    }

    private void paid(Source source, long cents) {
        history.add(new LoanTransaction(Ids.newId(), loanRow.id(), Type.RECEIPT, source, "TX-" + history.size(), null, cents, 0,
            null, NOW, DISBURSED, UUID.randomUUID(), "test"));
    }

    private PostingRequest posted() {
        ArgumentCaptor<PostingRequest> captor = ArgumentCaptor.forClass(PostingRequest.class);
        verify(ledger).post(captor.capture());
        return captor.getValue();
    }

    private static long line(PostingRequest request, AccountType type, Side side) {
        return request.lines().stream().filter(l -> l.account().type() == type && l.side() == side)
            .mapToLong(PostingRequest.Line::amountCents).sum();
    }

    @Test
    void aLoanNobodyPaidOnComesBackWholeAndNothingIsOwedToTheMember() {
        LoanApplication app = disbursedApplication();

        assertThat(unwinder.closeCancelled(app)).isTrue();

        PostingRequest entry = posted();
        assertThat(entry.sourceKey()).isEqualTo("loan-cooling-off:" + app.getId());
        assertThat(line(entry, AccountType.PROGRAM_CASH, Side.DEBIT)).isEqualTo(120_000);
        assertThat(line(entry, AccountType.LOANS_RECEIVABLE, Side.CREDIT)).isEqualTo(120_000);
        assertThat(entry.lines()).hasSize(2);
        verify(loans).updateDerived(loanRow.id(), LoanStatus.CANCELLED, 0, 0, 0, Bucket.CURRENT, DISBURSED, TODAY);
        verify(heldRefunds, never()).owe(any(), any(), any(), any(Long.class));
    }

    @Test
    void whatTheMemberPaidBeforeCancellingIsHeldAndOwedBackAndTheReceivableEndsAtZeroNotBelow() {
        paid(Source.CHECKOUT, 30_000);
        loanRow = row(LoanStatus.ACTIVE, 90_000, 0);
        LoanApplication app = disbursedApplication();

        unwinder.closeCancelled(app);

        PostingRequest entry = posted();
        assertThat(line(entry, AccountType.PROGRAM_CASH, Side.DEBIT)).isEqualTo(120_000);
        assertThat(line(entry, AccountType.LOANS_RECEIVABLE, Side.CREDIT))
            .as("only what was still owed: the receivable is 90,000, so crediting 120,000 would leave it negative")
            .isEqualTo(90_000);
        assertThat(line(entry, AccountType.MEMBER_OVERPAYMENT, Side.CREDIT)).as("the member's own money").isEqualTo(30_000);
        verify(loans).updateDerived(loanRow.id(), LoanStatus.CANCELLED, 0, 30_000, 0, Bucket.CURRENT, DISBURSED, TODAY);
        verify(heldRefunds).owe(loanRow.id(), MEMBER, "cancel-refund:" + loanRow.id(), 30_000);
        LendingEvents.LoanVoided v = (LendingEvents.LoanVoided) published.stream()
            .filter(LendingEvents.LoanVoided.class::isInstance).findFirst().orElseThrow();
        assertThat(v.reason()).isEqualTo(LendingEvents.LoanVoided.COOLING_OFF_CANCELLED);
        assertThat(v.refundDueCents()).isEqualTo(30_000);
    }

    @Test
    void aLoanTheMemberHadRepaidInFullAndThenCancelledIsRefundedInFull() {
        paid(Source.CHECKOUT, 120_000);
        loanRow = row(LoanStatus.REPAID, 0, 0);
        LoanApplication app = disbursedApplication();

        assertThat(unwinder.closeCancelled(app)).isTrue();

        PostingRequest entry = posted();
        assertThat(line(entry, AccountType.LOANS_RECEIVABLE, Side.CREDIT)).isZero();
        assertThat(line(entry, AccountType.MEMBER_OVERPAYMENT, Side.CREDIT)).isEqualTo(120_000);
        verify(loans).updateDerived(loanRow.id(), LoanStatus.CANCELLED, 0, 120_000, 0, Bucket.CURRENT, DISBURSED, TODAY);
        verify(heldRefunds).owe(loanRow.id(), MEMBER, "cancel-refund:" + loanRow.id(), 120_000);
    }

    @Test
    void whatTheFundPaidGoesBackToTheFundAndIsNotTheMembers() {
        paid(Source.CHECKOUT, 10_000);
        paid(Source.RESERVE, 20_000);
        loanRow = row(LoanStatus.ACTIVE, 90_000, 0);
        LoanApplication app = disbursedApplication();

        unwinder.closeCancelled(app);

        PostingRequest entry = posted();
        assertThat(line(entry, AccountType.LOANS_RECEIVABLE, Side.CREDIT)).isEqualTo(90_000);
        assertThat(line(entry, AccountType.MEMBER_OVERPAYMENT, Side.CREDIT)).isEqualTo(10_000);
        assertThat(line(entry, AccountType.LOSS_RESERVE, Side.CREDIT)).isEqualTo(20_000);
        LendingEvents.LoanVoided v = (LendingEvents.LoanVoided) published.stream()
            .filter(LendingEvents.LoanVoided.class::isInstance).findFirst().orElseThrow();
        assertThat(v.refundDueCents()).isEqualTo(10_000);
        assertThat(v.reserveCoverCents()).isEqualTo(20_000);
    }

    @Test
    void aLoanThatIsAlreadyCancelledOrVoidIsLeftAloneSoAReplayPostsNothingTwice() {
        loanRow = row(LoanStatus.CANCELLED, 0, 0);
        assertThat(unwinder.closeCancelled(disbursedApplication())).isFalse();
        loanRow = row(LoanStatus.DISBURSEMENT_RETURNED, 0, 0);
        assertThat(unwinder.closeCancelled(disbursedApplication())).isFalse();

        verify(ledger, never()).post(any());
        verify(heldRefunds, never()).owe(any(), any(), eq("cancel-refund:" + loanRow.id()), any(Long.class));
    }
}
