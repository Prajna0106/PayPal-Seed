package com.paypalseed.lending.application.ports;

import com.paypalseed.lending.application.ports.PayoutRecipients.Recipient;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import java.util.UUID;

/**
 * Pays a loan out to the member through PayPal (the payments module). Idempotent on {@code referenceId}, which is
 * the application id: asking again never pays twice. The end of a payout that is still pending arrives later as an
 * event.
 */
public interface DisbursementPayouts {

    enum State {
        /** The money reached the member. */
        SUCCEEDED,
        /** PayPal has it and will report the end by webhook. */
        PENDING,
        /** PayPal refused it or could not deliver it. */
        FAILED
    }

    record Result(State state, String reasonCode) {
    }

    Result send(String referenceId, long amountCents, Recipient recipient, WalletType wallet, String note);

    /**
     * As above, saying whose loan it is, so the payments module can tell the fraud rules that a payout went to a
     * recipient (a change of recipient just before a disbursement is a signal). An implementation that does not care
     * about the member keeps the default, which drops it.
     */
    default Result send(String referenceId, UUID memberId, long amountCents, Recipient recipient, WalletType wallet, String note) {
        return send(referenceId, amountCents, recipient, wallet, note);
    }
}
