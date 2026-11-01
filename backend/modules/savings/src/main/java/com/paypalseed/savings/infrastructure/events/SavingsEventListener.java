package com.paypalseed.savings.infrastructure.events;

import com.paypalseed.payments.api.events.PaymentEvents;
import com.paypalseed.savings.application.ports.CreditedPayments.Source;
import com.paypalseed.savings.application.usecases.CreditPaymentUseCase;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase;
import com.paypalseed.savings.application.usecases.SubscriptionStateUseCase.State;
import com.paypalseed.savings.application.usecases.WithdrawalOutcomeUseCase;
import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * What PayPal said, as the payments module announces it. Listeners run after the publishing transaction
 * commits, in their own; a failure leaves the publication incomplete so it is delivered again (at least
 * once). Every use case below is idempotent on a business key (the PayPal transaction id, the withdrawal
 * id, the subscription state), so a repeat changes nothing.
 */
@Component
class SavingsEventListener {

    private final CreditPaymentUseCase credits;
    private final SubscriptionStateUseCase subscriptions;
    private final WithdrawalOutcomeUseCase withdrawals;

    SavingsEventListener(CreditPaymentUseCase credits, SubscriptionStateUseCase subscriptions, WithdrawalOutcomeUseCase withdrawals) {
        this.credits = credits;
        this.subscriptions = subscriptions;
        this.withdrawals = withdrawals;
    }

    @ApplicationModuleListener
    void on(PaymentEvents.SavingsDepositReceived e) {
        credits.credit(e.goalId(), e.paypalTxnId(), e.amountCents(), Source.SUBSCRIPTION);
    }

    @ApplicationModuleListener
    void on(PaymentEvents.SavingsTopUpCaptured e) {
        credits.credit(e.goalId(), e.paypalCaptureId(), e.amountCents(), Source.TOPUP);
    }

    @ApplicationModuleListener
    void on(PaymentEvents.SavingsSubscriptionStateChanged e) {
        subscriptions.apply(e.goalId(), e.subscriptionId(), State.valueOf(e.state().name()), e.occurredAt());
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PayoutSucceeded e) {
        withdrawalId(e.referenceId()).ifPresent(id -> withdrawals.succeeded(id, e.paypalTransactionId()));
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PayoutFailed e) {
        withdrawalId(e.referenceId()).ifPresent(id -> withdrawals.failed(id, e.reasonCode(), e.terminal()));
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PayoutReturned e) {
        withdrawalId(e.referenceId()).ifPresent(id -> withdrawals.returned(id, e.reasonCode()));
    }

    /** Payout references of other modules (a loan disbursement) are also announced; they are not withdrawals. */
    private static Optional<UUID> withdrawalId(String referenceId) {
        try {
            return Optional.of(UUID.fromString(referenceId));
        } catch (IllegalArgumentException notOurs) {
            return Optional.empty();
        }
    }
}
