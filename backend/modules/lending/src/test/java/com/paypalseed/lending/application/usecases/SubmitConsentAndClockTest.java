package com.paypalseed.lending.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.lending.api.dto.LendingDtos.ApplicationPreviewView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.PreviewApplicationCommand;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.infrastructure.persistence.InMemoryLoanApplicationRepository;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fair-check consent a member gives with an application stays only if the application is accepted (ADR 0024 closing
 * pass and ADR 0026), a preview says when its income is out of date, and the service stamps its records with the
 * business clock (so they agree with what the unwind stamps).
 */
class SubmitConsentAndClockTest {

    private static final UUID ANA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ErrorCode UNAVAILABLE = new ErrorCode("income-source-unavailable", ErrorCode.Kind.UNAVAILABLE, "Income unavailable");

    /** Underwriting as lending sees it: a consent that the income preparation records, and one that can be taken back. */
    static class ConsentTrackingUnderwriting implements UnderwritingFacade {
        final List<String> calls = new ArrayList<>();
        boolean consented;
        boolean preparationRecordsConsent = true;
        DomainException refreshFailure;
        RuntimeException revokeFailure;

        @Override
        public Optional<IncomeSummary> incomeSummary(UUID memberId, LocalDate asOf) {
            return Optional.empty();
        }

        @Override
        public ConsentStatusView getConsentStatus(UUID memberId) {
            return new ConsentStatusView(memberId, consented, null, List.of(), List.of());
        }

        @Override
        public void prepareIncomeForApplication(UUID memberId) {
            calls.add("prepare");
            if (preparationRecordsConsent) {
                consented = true;
            }
        }

        @Override
        public void refreshIncomeIfConsented(UUID memberId) {
            calls.add("refresh");
            if (refreshFailure != null) {
                throw refreshFailure;
            }
        }

        @Override
        public ConsentStatusView revokeConsent(UUID memberId) {
            calls.add("revoke");
            if (revokeFailure != null) {
                throw revokeFailure;
            }
            consented = false;
            return getConsentStatus(memberId);
        }
    }

    private final FakeBusinessClock clock = new FakeBusinessClock(LocalDate.of(2026, 11, 1));
    private InMemoryLoanApplicationRepository repository;
    private ConsentTrackingUnderwriting underwriting;
    private MicroloanApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryLoanApplicationRepository();
        underwriting = new ConsentTrackingUnderwriting();
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
        service = new MicroloanApplicationService(repository, savings, null, null, clock, underwriting, null);
    }

    private SubmitApplicationCommand submit(long cents) {
        return new SubmitApplicationCommand("Commercial stand mixer", cents, 8, 15, WalletType.PAYPAL, true, OptionalLong.of(160_000L));
    }

    @Test
    void aRefusedApplicationTakesBackTheConsentItAloneGave() {
        assertThatThrownBy(() -> service.submitApplication(ANA, submit(5_000_000L)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(LendingErrors.SIZING_RULE_VIOLATION));

        assertThat(underwriting.calls).containsExactly("prepare", "revoke");
        assertThat(underwriting.consented).isFalse();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void aConsentTheMemberGaveEarlierSurvivesARefusedApplication() {
        underwriting.consented = true;

        assertThatThrownBy(() -> service.submitApplication(ANA, submit(5_000_000L))).isInstanceOf(DomainException.class);

        assertThat(underwriting.calls).containsExactly("prepare");
        assertThat(underwriting.consented).isTrue();
    }

    @Test
    void anAcceptedApplicationKeepsTheConsent() {
        LoanApplicationView view = service.submitApplication(ANA, submit(120_000L));

        assertThat(view.status()).isEqualTo("SUBMITTED");
        assertThat(underwriting.calls).containsExactly("prepare");
        assertThat(underwriting.consented).isTrue();
    }

    @Test
    void whenNothingWasRecordedBeforeTheRefusalNothingIsRevoked() {
        underwriting.preparationRecordsConsent = false; // the seeded source: the consent is only written inside the transaction

        assertThatThrownBy(() -> service.submitApplication(ANA, submit(5_000_000L))).isInstanceOf(DomainException.class);

        assertThat(underwriting.calls).containsExactly("prepare");
    }

    @Test
    void aFailureToTakeTheConsentBackNeverHidesTheRefusal() {
        underwriting.revokeFailure = new IllegalStateException("database down");

        assertThatThrownBy(() -> service.submitApplication(ANA, submit(5_000_000L)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(LendingErrors.SIZING_RULE_VIOLATION));
    }

    @Test
    void aPreviewSaysWhenPayPalCouldNotBeReadAndAnswersFromTheStoredIncome() {
        ApplicationPreviewView fresh = service.previewApplication(ANA,
                new PreviewApplicationCommand(120_000L, 8, 15, OptionalLong.of(160_000L)));
        assertThat(fresh.incomeStale()).isFalse();

        underwriting.refreshFailure = new DomainException(UNAVAILABLE, "PayPal income could not be read");
        ApplicationPreviewView stale = service.previewApplication(ANA,
                new PreviewApplicationCommand(120_000L, 8, 15, OptionalLong.of(160_000L)));

        assertThat(stale.incomeStale()).isTrue();
        assertThat(stale.valid()).as("the answer still comes from the stored income").isTrue();
        assertThat(stale.monthlyInstalmentCents()).isEqualTo(fresh.monthlyInstalmentCents());
    }

    @Test
    void recordsAreStampedWithTheBusinessClockNotTheWallClock() {
        LoanApplicationView view = service.submitApplication(ANA, submit(120_000L));

        assertThat(view.createdAt()).isEqualTo(clock.now());
        service.approveApplication(view.id(), UUID.randomUUID());
        clock.set(LocalDate.of(2026, 11, 3));
        LoanApplicationView approved = service.approveApplication(view.id(), UUID.randomUUID());

        assertThat(approved.updatedAt()).isEqualTo(clock.now());
    }
}
