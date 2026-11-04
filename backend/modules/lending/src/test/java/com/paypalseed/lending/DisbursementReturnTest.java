package com.paypalseed.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.ledger.api.dto.Side;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.application.usecases.DisbursementReturn;
import com.paypalseed.lending.application.usecases.LoanRecalculator;
import com.paypalseed.lending.domain.model.LoanApplication;
import com.paypalseed.lending.domain.model.LoanApplication.ApplicationStatus;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What the unwind of a returned loan posts and publishes (ADR 0024), over the real allocation engine with the storage
 * faked: the entry balances, clears the receivable, holds what the member paid for them and hands the fund's part
 * back; a second call or a loan that is already void does nothing.
 */
class DisbursementReturnTest {

    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate DISBURSED = LocalDate.of(2026, 11, 10);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Schedule SCHEDULE = ScheduleGenerator.generate(120_000, 8, 15, DISBURSED, RepaymentPolicy.V1);

    private final List<LoanTransaction> history = new ArrayList<>();
    private final List<Object> published = new ArrayList<>();
    private LoanRow loanRow;
    private LoanRepository loans;
    private TransactionRepository transactions;
    private TimerRepository timers;
    private AttemptRepository attempts;
    private LoanRecalculator recalculator;
    private LedgerFacade ledger;
    private GovernanceFacade governance;
    private DisbursementReturn unwinder;

    @BeforeEach
    void setUp() {
        loans = mock(LoanRepository.class);
        transactions = mock(TransactionRepository.class);
        timers = mock(TimerRepository.class);
        attempts = mock(AttemptRepository.class);
        recalculator = mock(LoanRecalculator.class);
        ledger = mock(LedgerFacade.class);
        governance = mock(GovernanceFacade.class);
        ApplicationEventPublisher events = published::add;
        loanRow = row(LoanStatus.ACTIVE);

        when(loans.lock(loanRow.id())).thenAnswer(i -> Optional.of(loanRow));
        when(transactions.forLoan(loanRow.id())).thenAnswer(i -> List.copyOf(history));
        when(recalculator.project(any(), any())).thenAnswer(i -> new LoanRecalculator.Projection(SCHEDULE,
            Allocator.allocate(SCHEDULE, history.stream().map(LoanTransaction::toMoneyEvent).toList(), RepaymentPolicy.V1)));
        unwinder = new DisbursementReturn(loans, transactions, timers, attempts, recalculator, ledger, governance,
            new FakeBusinessClock(TODAY), events);
    }

    private static LoanRow row(LoanStatus status) {
        return new LoanRow(UUID.fromString("22222222-2222-2222-2222-222222222222"), MEMBER, 120_000, 8, 15, DISBURSED,
            RepaymentPolicy.V1.version(), status, CollectionMode.MANUAL, 1, 120_000, 0, 0, Bucket.CURRENT, DISBURSED, null, null, 1);
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
    void aLoanNobodyHasPaidOnIsReversedInFullAndOwesNothing() {
        LoanApplication app = disbursedApplication();

        boolean voided = unwinder.unwind(app, app.getId().toString(), "RETURNED");

        assertThat(voided).isTrue();
        PostingRequest entry = posted();
        assertThat(entry.sourceKey()).isEqualTo("loan-void:" + loanRow.id());
        assertThat(line(entry, AccountType.PROGRAM_CASH, Side.DEBIT)).isEqualTo(120_000);
        assertThat(line(entry, AccountType.LOANS_RECEIVABLE, Side.CREDIT)).isEqualTo(120_000);
        assertThat(entry.lines()).hasSize(2);
        verify(loans).updateDerived(loanRow.id(), LoanStatus.DISBURSEMENT_RETURNED, 0, 0, 0, Bucket.CURRENT, DISBURSED, TODAY);
        verify(timers).replacePlanned(loanRow.id(), Set.of());
        verify(attempts).cancelPlanned(loanRow.id(), "loan voided");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(published).filteredOn(LendingEvents.LoanVoided.class::isInstance).singleElement().satisfies(e -> {
            LendingEvents.LoanVoided v = (LendingEvents.LoanVoided) e;
            assertThat(v.refundDueCents()).isZero();
            assertThat(v.reserveCoverCents()).isZero();
            assertThat(v.reason()).isEqualTo("RETURNED");
        });
        assertThat(published).filteredOn(LendingEvents.MandateSyncRequested.class::isInstance).hasSize(1);
        verify(governance).audit(any(AuditCommand.class));
    }

    @Test
    void whatTheMemberPaidIsHeldForThemAndWhatTheFundPaidGoesBackToTheFund() {
        paid(Source.CHECKOUT, 30_000);
        paid(Source.RESERVE, 20_000);
        LoanApplication app = disbursedApplication();

        unwinder.unwind(app, app.getId().toString(), "UNCLAIMED_EXPIRED");

        PostingRequest entry = posted();
        assertThat(line(entry, AccountType.PROGRAM_CASH, Side.DEBIT)).isEqualTo(120_000);
        assertThat(line(entry, AccountType.LOANS_RECEIVABLE, Side.CREDIT)).as("what was still owed").isEqualTo(70_000);
        assertThat(line(entry, AccountType.MEMBER_OVERPAYMENT, Side.CREDIT)).as("the member's own money").isEqualTo(30_000);
        assertThat(line(entry, AccountType.LOSS_RESERVE, Side.CREDIT)).as("the fund's money").isEqualTo(20_000);
        verify(loans).updateDerived(loanRow.id(), LoanStatus.DISBURSEMENT_RETURNED, 0, 30_000, 0, Bucket.CURRENT, DISBURSED, TODAY);
        LendingEvents.LoanVoided v = (LendingEvents.LoanVoided) published.stream()
            .filter(LendingEvents.LoanVoided.class::isInstance).findFirst().orElseThrow();
        assertThat(v.refundDueCents()).isEqualTo(30_000);
        assertThat(v.reserveCoverCents()).isEqualTo(20_000);
        assertThat(v.principalCents()).isEqualTo(120_000);
    }

    @Test
    void aMemberWhoOverpaidKeepsTheOverpaymentAlreadyHeldAndItJoinsTheRefund() {
        paid(Source.CHECKOUT, 125_000);
        loanRow = new LoanRow(loanRow.id(), MEMBER, 120_000, 8, 15, DISBURSED, RepaymentPolicy.V1.version(), LoanStatus.REPAID,
            CollectionMode.MANUAL, 1, 0, 5_000, 0, Bucket.CURRENT, DISBURSED, null, DISBURSED, 2);
        LoanApplication app = disbursedApplication();

        unwinder.unwind(app, app.getId().toString(), "RETURNED");

        PostingRequest entry = posted();
        assertThat(line(entry, AccountType.LOANS_RECEIVABLE, Side.CREDIT)).isZero();
        assertThat(line(entry, AccountType.MEMBER_OVERPAYMENT, Side.CREDIT)).as("only the part applied is new").isEqualTo(120_000);
        verify(loans).updateDerived(loanRow.id(), LoanStatus.DISBURSEMENT_RETURNED, 0, 125_000, 0, Bucket.CURRENT, DISBURSED, TODAY);
    }

    @Test
    void anApplicationThatIsNotDisbursedOrWhoseLoanIsAlreadyVoidIsLeftAlone() {
        LoanApplication approved = LoanApplication.submit(MEMBER, "Mixer", 120_000, 8, 15, WalletType.PAYPAL, true, LoanTier.LEVEL_1,
            15_000, 15_000, List.of(), NOW);
        approved.approve(UUID.randomUUID(), NOW);
        approved.approve(UUID.randomUUID(), NOW);
        assertThat(unwinder.unwind(approved, approved.getId().toString(), "RETURNED")).isFalse();

        loanRow = row(LoanStatus.DISBURSEMENT_RETURNED);
        LoanApplication disbursed = disbursedApplication();
        assertThat(unwinder.unwind(disbursed, disbursed.getId().toString(), "RETURNED")).isFalse();

        verify(ledger, never()).post(any());
        verify(governance, never()).audit(any());
        assertThat(published).isEmpty();
        assertThat(disbursed.getStatus()).isEqualTo(ApplicationStatus.DISBURSED);
    }

    @Test
    void theReasonThatReachesTheAuditChainIsPlainCharactersOnly() {
        LoanApplication app = disbursedApplication();

        unwinder.unwind(app, app.getId().toString(), "bad \"reason\"\n{}");

        ArgumentCaptor<AuditCommand> captor = ArgumentCaptor.forClass(AuditCommand.class);
        verify(governance).audit(captor.capture());
        assertThat(captor.getValue().entryType()).isEqualTo("LOAN_DISBURSEMENT_RETURNED");
        assertThat(captor.getValue().subjectId()).isEqualTo(loanRow.id());
        assertThat(captor.getValue().payloadJson()).contains("\"reason\":\"bad__reason____\"").doesNotContain("\n");
    }
}
