package com.paypalseed.payments.api.dto;

import com.paypalseed.engines.schedule.MandateReconciliation.MandateStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PaymentsDtos {

    private PaymentsDtos() {
    }

    public record StartMandate(UUID loanId, UUID memberId, LocalDate firstChargeOn, long quantity,
                               String returnUrl, String cancelUrl) {
        public StartMandate {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(firstChargeOn, "firstChargeOn");
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity must be at least 1 dollar");
            }
        }
    }

    public record MandateView(UUID mandateId, UUID loanId, MandateStatus status, Optional<String> subscriptionId,
                              Optional<String> approveUrl, long quantity, Optional<LocalDate> nextBillingDate) {
    }

    public enum OrderPurpose {
        CATCH_UP,
        EARLY,
        PAYOFF,
        PAY_WHAT_YOU_CAN
    }

    public record CreateOrder(UUID loanId, OrderPurpose purpose, long amountCents, String idempotencyKey) {
        public CreateOrder {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        }
    }

    public record CheckoutOrder(String orderId, String status, Optional<String> approveUrl, long amountCents) {
    }

    public record CapturedOrder(String orderId, String paypalTxnId, String status, long amountCents) {
    }

    /** Payments picks which captures to refund (newest first); each completed refund is an event. */
    public record RefundOverpayment(UUID loanId, long amountCents, String idempotencyKey) {
        public RefundOverpayment {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            if (amountCents <= 0) {
                throw new IllegalArgumentException("refund amount must be positive");
            }
        }
    }

    public record RefundResult(List<String> paypalRefundIds, long amountCents) {
        public RefundResult {
            paypalRefundIds = List.copyOf(paypalRefundIds);
        }
    }

    // ---- Billing catalog and savings (ADR 0013) -------------------------------------------------

    /** The PayPal product and the plans Seed bills on. Plans charge $1 per unit; quantity is whole dollars. */
    public record PaypalCatalog(String productId, String savingsPlanId, String loanPlanId) {
    }

    /**
     * Starts a monthly deposit into a goal. {@code requestId} is the caller's idempotency key: the same
     * (goal, requestId) returns the same subscription, and a different body under it is a {@code 409}.
     */
    public record StartSavingsSubscription(UUID memberId, UUID goalId, long monthlyCents, String requestId,
                                           String returnUrl, String cancelUrl) {
        public StartSavingsSubscription {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(goalId, "goalId");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(returnUrl, "returnUrl");
            Objects.requireNonNull(cancelUrl, "cancelUrl");
            if (requestId.isBlank() || requestId.length() > 128) {
                throw new IllegalArgumentException("requestId must be 1 to 128 characters");
            }
            if (monthlyCents < 100 || monthlyCents % 100 != 0) {
                throw new IllegalArgumentException("monthly amount must be a whole number of dollars, at least $1");
            }
        }
    }

    /** {@code approveUrl} is where the member consents; the subscription bills only after that. */
    public record SavingsSubscriptionView(UUID goalId, String subscriptionId, String status, Optional<String> approveUrl,
                                          long monthlyCents) {
    }

    public enum FundingSource {
        PAYPAL,
        /** One-off Orders only: PayPal Subscriptions cannot be funded with Venmo. */
        VENMO
    }

    /** A one-off deposit into a goal through Orders v2, funded by PayPal or Venmo. */
    public record SavingsTopUp(UUID memberId, UUID goalId, long amountCents, FundingSource funding, String idempotencyKey) {
        public SavingsTopUp {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(goalId, "goalId");
            Objects.requireNonNull(funding, "funding");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            if (amountCents <= 0) {
                throw new IllegalArgumentException("top-up amount must be positive");
            }
        }
    }

    // ---- Payouts --------------------------------------------------------------------------------

    public enum PayoutWallet {
        PAYPAL,
        VENMO
    }

    public enum RecipientType {
        PAYPAL_ID,
        EMAIL,
        /** Venmo and PayPal both accept a phone number; international format with the country code. */
        PHONE
    }

    public enum PayoutStatus {
        REQUESTED,
        PENDING,
        SUCCEEDED,
        FAILED,
        RETURNED,
        BLOCKED,
        /** The recipient has no PayPal account yet; PayPal holds the money up to 30 days, then returns it. */
        UNCLAIMED;

        /** No further change can happen. UNCLAIMED and SUCCEEDED can still become RETURNED or SUCCEEDED. */
        public boolean isFinal() {
            return this == FAILED || this == BLOCKED || this == RETURNED;
        }
    }

    /**
     * One payout to one recipient. {@code referenceId} is the caller's idempotency key (for example a
     * loan disbursement reference): the same reference with another amount or recipient is a {@code 409}.
     */
    public record SendPayout(String referenceId, long amountCents, RecipientType recipientType, String recipient,
                             PayoutWallet wallet, String note) {
        public SendPayout {
            Objects.requireNonNull(referenceId, "referenceId");
            Objects.requireNonNull(recipientType, "recipientType");
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(wallet, "wallet");
            if (referenceId.isBlank() || referenceId.length() > 56) {
                throw new IllegalArgumentException("referenceId must be 1 to 56 characters");
            }
            if (amountCents <= 0) {
                throw new IllegalArgumentException("payout amount must be positive");
            }
            if (recipient.isBlank() || recipient.length() > 127) {
                throw new IllegalArgumentException("recipient must be 1 to 127 characters");
            }
            if (note != null && note.length() > 4000) {
                throw new IllegalArgumentException("note is too long");
            }
        }
    }

    public record PayoutView(UUID payoutId, String referenceId, PayoutStatus status, long amountCents, PayoutWallet wallet,
                             Optional<String> paypalBatchId, Optional<String> paypalItemId,
                             Optional<String> paypalTransactionId, long feeCents, Optional<String> failureReason) {
    }

    // ---- Transaction Search ---------------------------------------------------------------------

    public enum CounterpartyKind {
        /** A person paid the account. */
        PERSON,
        /** A business or marketplace paid the account. */
        BUSINESS,
        /** A mass payment or payout, typically wages from a platform. */
        PAYOUT,
        /** A recurring payment. */
        SUBSCRIPTION,
        UNKNOWN
    }

    /**
     * Money that came into the account, with fees and refunds netted out the way PayPal reports them.
     * {@code netCents = grossCents - feeCents - refundedCents}.
     */
    public record IncomeCredit(String transactionId, LocalDate date, long grossCents, long feeCents, long refundedCents,
                               long netCents, CounterpartyKind counterparty, String description) {
    }
}
