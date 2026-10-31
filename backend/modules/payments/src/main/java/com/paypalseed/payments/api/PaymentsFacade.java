package com.paypalseed.payments.api;

import com.paypalseed.engines.schedule.MandateReconciliation.CollectionSpec;
import com.paypalseed.payments.api.dto.PaymentsDtos.CapturedOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CheckoutOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CreateOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.MandateView;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundOverpayment;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundResult;
import com.paypalseed.payments.api.dto.PaymentsDtos.StartMandate;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Money movement through PayPal. Payments is the only module holding PayPal write credentials
 * (INV-10). Outcomes arrive as {@code PaymentEvents}. Synchronous results only feed the member's
 * screen; they are never the system of record.
 */
public interface PaymentsFacade {

    /** Starts autopay; idempotent per loan while an approval is pending. */
    MandateView startMandate(StartMandate command);

    /** Return-URL confirmation; safe to race with the activation webhook. */
    MandateView confirmMandate(UUID loanId, String subscriptionId);

    Optional<MandateView> mandateOf(UUID loanId);

    /** Cancels the live mandate, if any (repaid loan, member switched to manual payments). */
    void cancelMandate(UUID loanId, String reason);

    /** Drives the live mandate toward what the schedule says (ADR 0005). */
    void reconcileMandate(UUID loanId, CollectionSpec spec);

    CheckoutOrder createOrder(CreateOrder command);

    /** Captures an approved order; the money fact itself still arrives as a {@code PaymentCaptured} event. */
    CapturedOrder captureOrder(UUID loanId, String orderId);

    /** The single automatic retry of a failed autopay charge (outstanding-balance capture). */
    void retryCapture(UUID loanId, UUID attemptId, long amountCents);

    /** Watchdog: reads recent autopay transactions and emits any PayPal knows about but we do not. */
    void checkRecentPayments(UUID loanId, LocalDate since);

    long refundableCents(UUID loanId);

    RefundResult refundOverpayment(RefundOverpayment command);
}
