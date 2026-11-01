package com.paypalseed.lending.infrastructure.clients;

import com.paypalseed.lending.application.ports.DisbursementPayouts;
import com.paypalseed.lending.application.ports.PayoutRecipients.Recipient;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutView;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import com.paypalseed.payments.api.dto.PaymentsDtos.SendPayout;
import com.paypalseed.platform.kernel.DomainException;
import org.springframework.stereotype.Component;

/** Lending's view of the payments module: its wallet and recipient types, mapped from lending's own. */
@Component
class PaymentsPayoutAdapter implements DisbursementPayouts {

    private final PaymentsFacade payments;

    PaymentsPayoutAdapter(PaymentsFacade payments) {
        this.payments = payments;
    }

    @Override
    public Result send(String referenceId, long amountCents, Recipient recipient, WalletType wallet, String note) {
        // A PayPal wallet is paid by payer id, which survives an email change; Venmo has no payer id and takes the email.
        boolean byPayerId = wallet != WalletType.VENMO && recipient.payerId() != null;
        String receiver = byPayerId ? recipient.payerId() : recipient.email();
        if (receiver == null) {
            throw new DomainException(LendingErrors.PAYOUT_RECIPIENT_MISSING, "The member has no account on file for this wallet");
        }
        PayoutView view = payments.sendPayout(new SendPayout(referenceId, amountCents,
            byPayerId ? RecipientType.PAYPAL_ID : RecipientType.EMAIL, receiver,
            wallet == WalletType.VENMO ? PayoutWallet.VENMO : PayoutWallet.PAYPAL, note));
        State state = switch (view.status()) {
            case SUCCEEDED -> State.SUCCEEDED;
            case FAILED, BLOCKED, RETURNED -> State.FAILED;
            case REQUESTED, PENDING, UNCLAIMED -> State.PENDING;
        };
        return new Result(state, view.failureReason().orElse(null));
    }
}
