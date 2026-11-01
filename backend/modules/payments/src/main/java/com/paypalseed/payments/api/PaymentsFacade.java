package com.paypalseed.payments.api;

import com.paypalseed.engines.schedule.MandateReconciliation.CollectionSpec;
import com.paypalseed.payments.api.dto.PaymentsDtos.CapturedOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CheckoutOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CreateOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.IncomeCredit;
import com.paypalseed.payments.api.dto.PaymentsDtos.MandateView;
import com.paypalseed.payments.api.dto.PaymentsDtos.PaypalCatalog;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutView;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundOverpayment;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundResult;
import com.paypalseed.payments.api.dto.PaymentsDtos.SavingsSubscriptionView;
import com.paypalseed.payments.api.dto.PaymentsDtos.SavingsTopUp;
import com.paypalseed.payments.api.dto.PaymentsDtos.SendPayout;
import com.paypalseed.payments.api.dto.PaymentsDtos.StartMandate;
import com.paypalseed.payments.api.dto.PaymentsDtos.StartSavingsSubscription;
import java.time.LocalDate;
import java.util.List;
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

    // ---- Billing catalog and savings (ADR 0013) -------------------------------------------------

    /**
     * Finds or creates the Seed product and its plans at PayPal; safe to call any number of times.
     * Ids from configuration win, then ids Seed recorded, then a lookup by name, and only then a create.
     */
    PaypalCatalog ensureSavingsPlan();

    /**
     * Creates the member's monthly-deposit subscription on the savings plan ({@code custom_id = goal:<goalId>}).
     * The member must still approve it at {@code approveUrl}. Idempotent per (goal, requestId).
     */
    SavingsSubscriptionView createSavingsSubscription(StartSavingsSubscription command);

    /** The newest PayPal subscription of the goal (status as PayPal last reported it), if one was ever created. */
    Optional<SavingsSubscriptionView> savingsSubscriptionOf(UUID goalId);

    /**
     * Suspends the goal's subscription. Idempotent: a subscription that is already suspended, cancelled or expired
     * is left alone. {@code requestId} makes the PayPal call replay-safe; use a new one for each pause.
     */
    void suspendSavingsSubscription(UUID goalId, String requestId, String reason);

    /**
     * Activates a suspended subscription again. A cancelled or expired one cannot come back: that is
     * {@code 409 savings-subscription-ended} and the caller starts a new subscription.
     */
    void activateSavingsSubscription(UUID goalId, String requestId, String reason);

    /**
     * Changes the monthly amount (whole dollars, at least $1). Returns the URL where the member has to consent
     * when PayPal asks for it. A cancelled or expired subscription is left alone.
     */
    Optional<String> reviseSavingsSubscription(UUID goalId, long monthlyCents, String requestId);

    /** Cancels the goal's subscription; an ended one is left alone. */
    void cancelSavingsSubscription(UUID goalId, String requestId, String reason);

    /** One-off top-up order into a goal; the only way to save with Venmo. Idempotent on the key. */
    CheckoutOrder createSavingsTopUp(SavingsTopUp command);

    /** Captures an approved top-up; the money fact is announced once as a {@code SavingsTopUpCaptured} event. */
    CapturedOrder captureSavingsTopUp(UUID goalId, String orderId);

    // ---- Payouts ---------------------------------------------------------------------------------

    /**
     * Sends money out through PayPal Payouts (PayPal or Venmo wallet). Idempotent on {@code referenceId}; the
     * same reference with another amount or recipient is a {@code 409}. The outcome arrives as
     * {@code PayoutSucceeded}, {@code PayoutFailed} or {@code PayoutReturned}.
     */
    PayoutView sendPayout(SendPayout command);

    Optional<PayoutView> payoutOf(String referenceId);

    // ---- Transaction Search ----------------------------------------------------------------------

    /**
     * Credits into the account over a business-date range, fees and refunds netted out, oldest first.
     * {@code accountReference} is the PayPal payer id to read with partner permissions, or blank for the
     * REST app's own account. Refused with {@code transaction-search-disabled} unless switched on.
     */
    List<IncomeCredit> incomeTransactions(String accountReference, LocalDate from, LocalDate to);
}
