package com.paypalseed.lending.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.lending.api.dto.LendingDtos.ApplicationPreviewView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.PreviewApplicationCommand;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.infrastructure.persistence.InMemoryLoanApplicationRepository;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The slow PayPal income read happens before the submission or preview transaction opens, a failed read leaves nothing
 * behind, and the work inside the transaction never waits for PayPal (ADR 0026).
 */
class SubmitIncomePreparationTest {

    private static final UUID ANA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ErrorCode UNAVAILABLE = new ErrorCode("income-source-unavailable", ErrorCode.Kind.UNAVAILABLE, "Income unavailable");

    /** Counts open transactions, so a test can see whether one was open at a given moment. */
    static class CountingTransactions implements PlatformTransactionManager {
        int open;
        int begun;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            open++;
            begun++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            open--;
        }

        @Override
        public void rollback(TransactionStatus status) {
            open--;
        }
    }

    /** Records what the lending service asks of underwriting, and what state the world was in when it asked. */
    class RecordingUnderwriting implements UnderwritingFacade {
        final List<String> calls = new ArrayList<>();
        int openTransactionsDuringCall = -1;
        int applicationsStoredDuringCall = -1;
        DomainException failure;

        @Override
        public Optional<IncomeSummary> incomeSummary(UUID memberId, LocalDate asOf) {
            return Optional.empty();
        }

        @Override
        public void prepareIncomeForApplication(UUID memberId) {
            record("prepare");
        }

        @Override
        public void refreshIncomeIfConsented(UUID memberId) {
            record("refresh");
        }

        private void record(String call) {
            calls.add(call);
            openTransactionsDuringCall = transactions.open;
            applicationsStoredDuringCall = repository.findAll().size();
            if (failure != null) {
                throw failure;
            }
        }
    }

    private InMemoryLoanApplicationRepository repository;
    private CountingTransactions transactions;
    private RecordingUnderwriting underwriting;
    private MicroloanApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryLoanApplicationRepository();
        transactions = new CountingTransactions();
        underwriting = new RecordingUnderwriting();
        SavingsFacade savings = new SavingsFacade() {
            @Override
            public void recordDepositBySubscription(String subscriptionId, Money amount, String payPalCaptureId) {
            }

            @Override
            public void pauseGoalBySubscription(String subscriptionId) {
            }

            @Override
            public MemberSavingsSummary getMemberSavingsSummary(String memberId) {
                return new MemberSavingsSummary(memberId, 4, 4, 0, 7_500L, 30_000L, true);
            }
        };
        service = new MicroloanApplicationService(repository, savings, null, null,
                new FakeBusinessClock(LocalDate.of(2026, 11, 1)), underwriting, null);
        service.useTransactions(transactions);
    }

    private SubmitApplicationCommand submit(boolean fairCheckConsented) {
        return new SubmitApplicationCommand("Commercial stand mixer", 120_000L, 8, 15, WalletType.PAYPAL,
                fairCheckConsented, OptionalLong.of(160_000L));
    }

    @Test
    void incomeIsPreparedBeforeTheSubmissionTransactionOpensAndBeforeAnythingIsStored() {
        LoanApplicationView view = service.submitApplication(ANA, submit(true));

        assertThat(underwriting.calls).containsExactly("prepare");
        assertThat(underwriting.openTransactionsDuringCall).isZero();
        assertThat(underwriting.applicationsStoredDuringCall).isZero();
        assertThat(transactions.begun).isEqualTo(1);
        assertThat(transactions.open).isZero();
        assertThat(repository.findAll()).hasSize(1);
        assertThat(view.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void aFailedIncomeReadOpensNoTransactionAndStoresNothing() {
        underwriting.failure = new DomainException(UNAVAILABLE, "PayPal income could not be read");

        assertThatThrownBy(() -> service.submitApplication(ANA, submit(true)))
                .isSameAs(underwriting.failure);

        assertThat(transactions.begun).isZero();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void withoutTheFairCheckConsentNothingIsReadAndTheApplicationIsRefused() {
        assertThatThrownBy(() -> service.submitApplication(ANA, submit(false)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(underwriting.calls).isEmpty();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void aPreviewRefreshesBeforeItsReadOnlyTransactionAndStoresNothing() {
        ApplicationPreviewView preview = service.previewApplication(ANA,
                new PreviewApplicationCommand(120_000L, 8, 15, OptionalLong.of(160_000L)));

        assertThat(underwriting.calls).containsExactly("refresh");
        assertThat(underwriting.openTransactionsDuringCall).isZero();
        assertThat(transactions.begun).isEqualTo(1);
        assertThat(preview.valid()).isTrue();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void aPreviewStillAnswersFromTheStoredIncomeWhenPayPalCannotBeReached() {
        underwriting.failure = new DomainException(UNAVAILABLE, "PayPal income could not be read");

        ApplicationPreviewView preview = service.previewApplication(ANA,
                new PreviewApplicationCommand(120_000L, 8, 15, OptionalLong.of(160_000L)));

        assertThat(preview.valid()).isTrue();
        assertThat(transactions.begun).isEqualTo(1);
    }
}
