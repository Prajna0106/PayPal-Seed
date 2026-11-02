package com.paypalseed.lending.application.ports;

import com.paypalseed.lending.application.ports.PayoutRecipients.Recipient;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;

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
}
