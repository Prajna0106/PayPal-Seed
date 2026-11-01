package com.paypalseed.savings.infrastructure.clients;

import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.FundingSource;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutStatus;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutView;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import com.paypalseed.payments.api.dto.PaymentsDtos.SavingsSubscriptionView;
import com.paypalseed.payments.api.dto.PaymentsDtos.SavingsTopUp;
import com.paypalseed.payments.api.dto.PaymentsDtos.SendPayout;
import com.paypalseed.payments.api.dto.PaymentsDtos.StartSavingsSubscription;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.savings.application.ports.PayoutRecipients;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsWallet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Savings' view of the payments module: its facade, translated to the few things savings needs to ask. */
@Component
class PaymentsAdapter implements SavingsPayments {

    private final PaymentsFacade payments;

    PaymentsAdapter(PaymentsFacade payments) {
        this.payments = payments;
    }

    @Override
    public Subscription startSubscription(UUID memberId, UUID goalId, long monthlyCents, String requestId, String returnUrl,
                                          String cancelUrl) {
        SavingsSubscriptionView view = payments.createSavingsSubscription(
            new StartSavingsSubscription(memberId, goalId, monthlyCents, requestId, returnUrl, cancelUrl));
        return new Subscription(view.subscriptionId(), view.status(), view.approveUrl());
    }

    @Override
    public Optional<Subscription> subscriptionOf(UUID goalId) {
        return payments.savingsSubscriptionOf(goalId).map(v -> new Subscription(v.subscriptionId(), v.status(), v.approveUrl()));
    }

    @Override
    public void suspend(UUID goalId, String requestId, String reason) {
        payments.suspendSavingsSubscription(goalId, requestId, reason);
    }

    @Override
    public void activate(UUID goalId, String requestId, String reason) {
        payments.activateSavingsSubscription(goalId, requestId, reason);
    }

    @Override
    public Optional<String> revise(UUID goalId, long monthlyCents, String requestId) {
        return payments.reviseSavingsSubscription(goalId, monthlyCents, requestId);
    }

    @Override
    public void cancel(UUID goalId, String requestId, String reason) {
        payments.cancelSavingsSubscription(goalId, requestId, reason);
    }

    @Override
    public Order startTopUp(UUID memberId, UUID goalId, long amountCents, SavingsWallet funding, String idempotencyKey) {
        var order = payments.createSavingsTopUp(new SavingsTopUp(memberId, goalId, amountCents,
            funding == SavingsWallet.VENMO ? FundingSource.VENMO : FundingSource.PAYPAL, idempotencyKey));
        return new Order(order.orderId(), order.status(), order.approveUrl());
    }

    @Override
    public OrderCapture captureTopUp(UUID goalId, String orderId) {
        var captured = payments.captureSavingsTopUp(goalId, orderId);
        return new OrderCapture(captured.orderId(), captured.status(), captured.amountCents());
    }

    @Override
    public Payout sendPayout(String referenceId, long amountCents, PayoutRecipients.Recipient recipient, SavingsWallet wallet,
                             String note) {
        // A PayPal wallet is paid by payer id, which survives an email change; Venmo has no payer id and takes the email.
        boolean byPayerId = wallet != SavingsWallet.VENMO && recipient.payerId() != null;
        String receiver = byPayerId ? recipient.payerId() : recipient.email();
        if (receiver == null) {
            throw new DomainException(SavingsErrors.PAYOUT_RECIPIENT_MISSING, "No account on file for this wallet");
        }
        PayoutView view = payments.sendPayout(new SendPayout(referenceId, amountCents,
            byPayerId ? RecipientType.PAYPAL_ID : RecipientType.EMAIL, receiver,
            wallet == SavingsWallet.VENMO ? PayoutWallet.VENMO : PayoutWallet.PAYPAL, note));
        PayoutState state = switch (view.status()) {
            case SUCCEEDED -> PayoutState.SUCCEEDED;
            case FAILED, BLOCKED, RETURNED -> PayoutState.FAILED;
            case REQUESTED, PENDING, UNCLAIMED -> PayoutState.PENDING;
        };
        return new Payout(state, view.failureReason().orElse(null), view.paypalTransactionId().orElse(null));
    }
}
