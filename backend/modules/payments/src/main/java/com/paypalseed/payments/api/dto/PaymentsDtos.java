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
}
