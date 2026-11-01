package com.paypalseed.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.api.events.LoanApplicationChanged;
import com.paypalseed.lending.application.ports.DisbursementPayouts;
import com.paypalseed.lending.application.ports.DisbursementPayouts.Result;
import com.paypalseed.lending.application.ports.DisbursementPayouts.State;
import com.paypalseed.lending.application.ports.PayoutRecipients;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.domain.model.StaffReferences;
import com.paypalseed.lending.infrastructure.persistence.InMemoryLoanApplicationRepository;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Approved, disbursing, disbursed: what each payout answer does to an application, with PayPal faked. */
class DisbursementPayoutSagaTest {

    private static final UUID ANA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ONE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TWO = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final InMemoryLoanApplicationRepository repository = new InMemoryLoanApplicationRepository();
    private final LendingFacade lending = mock(LendingFacade.class);
    private final List<Object> published = new ArrayList<>();
    private final List<String> payoutReferences = new ArrayList<>();
    private Result nextPayout = new Result(State.PENDING, null);
    private RuntimeException payoutBreaks;
    private Optional<PayoutRecipients.Recipient> email = Optional.of(new PayoutRecipients.Recipient("PAYER-ANA", "ana@example.com"));
    private MicroloanApplicationService service;
    private UUID applicationId;

    @BeforeEach
    void anApprovedApplication() {
        SavingsFacade savings = new SavingsFacade() {
            @Override
            public void recordDepositBySubscription(String subscriptionId, com.paypalseed.platform.kernel.Money amount, String id) {
            }

            @Override
            public void pauseGoalBySubscription(String subscriptionId) {
            }

            @Override
            public MemberSavingsSummary getMemberSavingsSummary(String memberId) {
                return new MemberSavingsSummary(memberId, 4, 4, 0, 7_500L, 30_000L, true);
            }
        };
        DisbursementPayouts payouts = (reference, cents, recipient, wallet, note) -> {
            payoutReferences.add(reference);
            if (payoutBreaks != null) {
                throw payoutBreaks;
            }
            return nextPayout;
        };
        PayoutRecipients recipients = memberId -> email;
        when(lending.registerDisbursedLoan(any())).thenAnswer(call -> {
            RegisterDisbursedLoan c = call.getArgument(0);
            return new LoanSummary(UUID.nameUUIDFromBytes(c.disbursementRef().getBytes()), c.memberId(), c.principalCents(),
                c.principalCents(), "ACTIVE", "MANUAL", "CURRENT", 0, 1);
        });
        service = new MicroloanApplicationService(repository, savings, null, lending, new FakeBusinessClock(LocalDate.of(2026, 11, 10)),
            null, null, payouts, recipients, published::add);
        LoanApplicationView submitted = service.submitApplication(ANA,
            new SubmitApplicationCommand("Mixer", 120_000L, 8, 15, WalletType.VENMO, true, OptionalLong.empty()));
        service.approveApplication(submitted.id(), ONE);
        service.approveApplication(submitted.id(), TWO);
        applicationId = submitted.id();
        published.clear();
    }

    private String status() {
        return service.getApplication(applicationId).status();
    }

    private List<String> publishedStatuses() {
        return published.stream().filter(LoanApplicationChanged.class::isInstance).map(LoanApplicationChanged.class::cast)
            .map(LoanApplicationChanged::status).toList();
    }

    @Test
    void aPayoutPayPalAnswersAtOnceRegistersTheLoanInTheSameCall() {
        nextPayout = new Result(State.SUCCEEDED, null);

        LoanApplicationView view = service.disburseApplication(applicationId, TWO);

        assertThat(view.status()).isEqualTo("DISBURSED");
        assertThat(view.loanId()).isNotNull();
        assertThat(view.disbursementRef()).isEqualTo("loan-" + applicationId + "-disb");
        assertThat(payoutReferences).containsExactly(applicationId.toString());
        verify(lending, times(1)).registerDisbursedLoan(any());
        assertThat(publishedStatuses()).containsExactly("DISBURSING", "DISBURSED");
    }

    @Test
    void aPendingPayoutLeavesTheApplicationDisbursingWithNoLoanUntilTheEventArrives() {
        LoanApplicationView view = service.disburseApplication(applicationId, TWO);

        assertThat(view.status()).isEqualTo("DISBURSING");
        assertThat(view.loanId()).isNull();
        verify(lending, never()).registerDisbursedLoan(any());

        service.onPayoutSucceeded(applicationId.toString());

        assertThat(status()).isEqualTo("DISBURSED");
        verify(lending, times(1)).registerDisbursedLoan(any());
    }

    @Test
    void theSuccessEventAndTheDisburseReplayRegisterTheLoanExactlyOnce() {
        service.disburseApplication(applicationId, TWO);

        service.onPayoutSucceeded(applicationId.toString());
        service.onPayoutSucceeded(applicationId.toString());
        LoanApplicationView replay = service.disburseApplication(applicationId, TWO);

        assertThat(replay.status()).isEqualTo("DISBURSED");
        verify(lending, times(1)).registerDisbursedLoan(any());
        assertThat(payoutReferences).as("a disbursed application is not paid again").hasSize(1);
    }

    @Test
    void askingAgainWhileDisbursingAsksPayPalForTheSamePayoutNotANewOne() {
        service.disburseApplication(applicationId, TWO);
        service.disburseApplication(applicationId, TWO);

        assertThat(payoutReferences).containsExactly(applicationId.toString(), applicationId.toString());
        assertThat(status()).isEqualTo("DISBURSING");
        assertThat(publishedStatuses()).as("one DISBURSING snapshot").containsExactly("DISBURSING");
    }

    @Test
    void aTerminalFailureReturnsTheApplicationToApprovedWithAReasonForTheOfficer() {
        service.disburseApplication(applicationId, TWO);

        service.onPayoutFailed(applicationId.toString(), "RECEIVER_UNREGISTERED", true);

        LoanApplicationView view = service.getApplication(applicationId);
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.payoutIssue()).isEqualTo("RECEIVER_UNREGISTERED");
        assertThat(view.loanId()).isNull();
        verify(lending, never()).registerDisbursedLoan(any());
    }

    @Test
    void aFailurePayPalAnswersAtOnceIsHandledInTheSameCall() {
        nextPayout = new Result(State.FAILED, "RECEIVER_BLOCKED");

        LoanApplicationView view = service.disburseApplication(applicationId, TWO);

        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.payoutIssue()).isEqualTo("RECEIVER_BLOCKED");
        assertThat(publishedStatuses()).containsExactly("DISBURSING", "APPROVED");
    }

    @Test
    void aRetryAfterATerminalFailureIsANewPayoutReference() {
        nextPayout = new Result(State.FAILED, "RECEIVER_BLOCKED");
        service.disburseApplication(applicationId, TWO);
        nextPayout = new Result(State.SUCCEEDED, null);

        LoanApplicationView view = service.disburseApplication(applicationId, TWO);

        assertThat(view.status()).isEqualTo("DISBURSED");
        assertThat(payoutReferences).containsExactly(applicationId.toString(), applicationId + "-a2");
    }

    @Test
    void aLateReportAboutAnEarlierAttemptChangesNothing() {
        nextPayout = new Result(State.FAILED, "RECEIVER_BLOCKED");
        service.disburseApplication(applicationId, TWO);
        nextPayout = new Result(State.PENDING, null);
        service.disburseApplication(applicationId, TWO);

        service.onPayoutSucceeded(applicationId.toString());

        assertThat(status()).isEqualTo("DISBURSING");
        verify(lending, never()).registerDisbursedLoan(any());
    }

    @Test
    void moneyHeldForARecipientWithNoAccountIsNotedAndTheApplicationKeepsWaiting() {
        service.disburseApplication(applicationId, TWO);

        service.onPayoutFailed(applicationId.toString(), "UNCLAIMED", false);

        assertThat(status()).isEqualTo("DISBURSING");
        assertThat(service.getApplication(applicationId).payoutIssue()).isEqualTo("UNCLAIMED");
        service.onPayoutReturned(applicationId.toString(), "UNCLAIMED_EXPIRED");
        assertThat(status()).isEqualTo("APPROVED");
        assertThat(service.getApplication(applicationId).payoutIssue()).isEqualTo("UNCLAIMED_EXPIRED");
    }

    @Test
    void aPayoutTakenBackAfterTheLoanWasRegisteredIsFlaggedNotUnwound() {
        nextPayout = new Result(State.SUCCEEDED, null);
        service.disburseApplication(applicationId, TWO);

        service.onPayoutReturned(applicationId.toString(), "RETURNED");

        LoanApplicationView view = service.getApplication(applicationId);
        assertThat(view.status()).isEqualTo("DISBURSED");
        assertThat(view.payoutIssue()).isEqualTo("RETURNED_AFTER_DISBURSEMENT");
    }

    @Test
    void theMemberCannotCancelWhileTheMoneyIsOnItsWay() {
        service.disburseApplication(applicationId, TWO);

        assertThatThrownBy(() -> service.cancelWithinCoolingOff(applicationId, ANA)).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(LendingErrors.APPLICATION_INVALID_STATE));
        assertThat(status()).isEqualTo("DISBURSING");
    }

    @Test
    void withoutAPayPalAccountOnFileTheApplicationStaysApprovedAndNothingIsSent() {
        email = Optional.empty();

        assertThatThrownBy(() -> service.disburseApplication(applicationId, TWO)).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(LendingErrors.PAYOUT_RECIPIENT_MISSING));
        assertThat(status()).isEqualTo("APPROVED");
        assertThat(payoutReferences).isEmpty();
    }

    @Test
    void anUnapprovedApplicationCannotBeDisbursed() {
        LoanApplicationView other = service.submitApplication(UUID.randomUUID(),
            new SubmitApplicationCommand("Oven", 120_000L, 8, 15, WalletType.PAYPAL, true, OptionalLong.empty()));

        assertThatThrownBy(() -> service.disburseApplication(other.id(), TWO)).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(LendingErrors.APPLICATION_INVALID_STATE));
        assertThat(payoutReferences).isEmpty();
    }

    @Test
    void referencesOfOtherPayoutsAreIgnored() {
        service.disburseApplication(applicationId, TWO);

        service.onPayoutSucceeded(UUID.randomUUID().toString());
        service.onPayoutSucceeded("not-a-uuid-at-all-and-longer-than-thirty-six-characters");
        service.onPayoutFailed("short", "X", true);

        assertThat(status()).isEqualTo("DISBURSING");
    }

    @Test
    void everyChangeCarriesTheWholeStateAndOnlyAPseudonymOfTheOfficer() {
        nextPayout = new Result(State.SUCCEEDED, null);
        service.disburseApplication(applicationId, TWO);

        List<LoanApplicationChanged> events = published.stream().filter(LoanApplicationChanged.class::isInstance)
            .map(LoanApplicationChanged.class::cast).toList();

        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.applicationId()).isEqualTo(applicationId);
            assertThat(e.memberId()).isEqualTo(ANA);
            assertThat(e.requestedAmountCents()).isEqualTo(120_000);
            assertThat(e.termMonths()).isEqualTo(8);
            assertThat(e.submittedAt()).isNotNull();
            assertThat(e.assignedOfficerRef()).isEqualTo(StaffReferences.of(TWO)).doesNotContain(TWO.toString());
            assertThat(e.eventId()).isNotNull();
        });
        assertThat(events.get(1).changedAt()).isAfterOrEqualTo(events.get(0).changedAt());
    }

    @Test
    void aBrokenPayPalCallPropagatesSoTheCallerCanRetryTheSameReference() {
        payoutBreaks = new IllegalStateException("PayPal is unavailable");

        assertThatThrownBy(() -> service.disburseApplication(applicationId, TWO)).hasMessageContaining("unavailable");
        payoutBreaks = null;
        nextPayout = new Result(State.SUCCEEDED, null);
        service.disburseApplication(applicationId, TWO);

        assertThat(status()).isEqualTo("DISBURSED");
        verify(lending, times(1)).registerDisbursedLoan(any());
    }
}
