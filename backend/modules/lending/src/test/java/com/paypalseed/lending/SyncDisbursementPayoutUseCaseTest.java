package com.paypalseed.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.ports.DisbursementPayouts;
import com.paypalseed.lending.application.ports.DisbursementPayouts.Outcome;
import com.paypalseed.lending.application.ports.DisbursementPayouts.Synced;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.SyncDisbursementPayoutUseCase;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The desk's "Check again" applies what PayPal says through the steps the payout events use (ADR 0029). */
class SyncDisbursementPayoutUseCaseTest {

    private static final UUID APPLICATION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String REFERENCE = APPLICATION.toString();

    private final MicroloanApplicationService applications = mock(MicroloanApplicationService.class);
    private final DisbursementPayouts payouts = mock(DisbursementPayouts.class);
    private final LoanApplicationView view = mock(LoanApplicationView.class);

    @SuppressWarnings("unchecked")
    private SyncDisbursementPayoutUseCase useCase(DisbursementPayouts wired) {
        ObjectProvider<DisbursementPayouts> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(wired);
        return new SyncDisbursementPayoutUseCase(applications, provider);
    }

    private void paypalSays(Outcome outcome, String reason) {
        when(applications.currentPayoutReference(APPLICATION)).thenReturn(Optional.of(REFERENCE));
        when(payouts.sync(REFERENCE)).thenReturn(Optional.of(new Synced(outcome, reason)));
        when(applications.getApplication(APPLICATION)).thenReturn(view);
    }

    @Test
    void aSucceededPayoutRegistersTheLoanThroughTheEventStep() {
        paypalSays(Outcome.SUCCEEDED, null);

        assertThat(useCase(payouts).execute(APPLICATION)).isSameAs(view);

        verify(applications).onPayoutSucceeded(REFERENCE);
    }

    @Test
    void aFailedPayoutIsTerminalAndAnUnclaimedOneIsNot() {
        paypalSays(Outcome.FAILED, "RECEIVER_UNREGISTERED");
        useCase(payouts).execute(APPLICATION);
        verify(applications).onPayoutFailed(REFERENCE, "RECEIVER_UNREGISTERED", true);

        paypalSays(Outcome.UNCLAIMED, null);
        useCase(payouts).execute(APPLICATION);
        verify(applications).onPayoutFailed(REFERENCE, "UNCLAIMED", false);
    }

    @Test
    void aReturnedPayoutUnwindsThroughTheReturnStep() {
        paypalSays(Outcome.RETURNED, "RETURNED");

        useCase(payouts).execute(APPLICATION);

        verify(applications).onPayoutReturned(REFERENCE, "RETURNED");
    }

    @Test
    void aPendingPayoutChangesNothing() {
        paypalSays(Outcome.PENDING, null);

        assertThat(useCase(payouts).execute(APPLICATION)).isSameAs(view);

        verify(applications, never()).onPayoutSucceeded(REFERENCE);
        verify(applications).currentPayoutReference(APPLICATION);
        verify(applications).getApplication(APPLICATION);
        verifyNoMoreInteractions(applications);
    }

    @Test
    void anApplicationThatWasNeverPaidOutHasNoPayout() {
        when(applications.currentPayoutReference(APPLICATION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase(payouts).execute(APPLICATION)).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(LendingErrors.PAYOUT_NOT_FOUND));
    }

    @Test
    void aPayoutPaymentsDoesNotKnowIsNotFoundEither() {
        when(applications.currentPayoutReference(APPLICATION)).thenReturn(Optional.of(REFERENCE));
        when(payouts.sync(REFERENCE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase(payouts).execute(APPLICATION)).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(LendingErrors.PAYOUT_NOT_FOUND));
        assertThatThrownBy(() -> useCase(null).execute(APPLICATION)).isInstanceOf(DomainException.class);
    }
}
