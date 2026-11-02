package com.paypalseed.payments.application.ports;

import com.paypalseed.payments.api.dto.PaymentsDtos.FundingSource;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PayPal REST operations Seed uses for repayments (04-paypal-integration §2–§7). The production
 * adapter calls PayPal; tests plug in a stateful simulator. Every write takes a {@code requestId},
 * sent as {@code PayPal-Request-Id}, so a replay after a timeout returns the original result.
 */
public interface PayPalGateway {

    record Subscription(String id, String status, long quantity, Optional<Instant> nextBillingTime,
                        Optional<String> approveUrl, Optional<String> customId) {
    }

    /** {@code startTime} may be null: the subscription then starts when the payer approves it. */
    record CreateSubscription(long quantity, String customId, Instant startTime, String returnUrl, String cancelUrl) {
    }

    /** {@code approveUrl} is present when PayPal needs the payer to consent to the change. */
    record Revision(Optional<String> approveUrl) {
    }

    record Order(String id, String status, Optional<String> approveUrl) {
    }

    /** {@code funding} picks the payment source; Venmo is available on Orders only, never on Subscriptions. */
    record CreateOrder(long amountCents, String customId, String invoiceId, String description, FundingSource funding) {
        public CreateOrder(long amountCents, String customId, String invoiceId, String description) {
            this(amountCents, customId, invoiceId, description, FundingSource.PAYPAL);
        }
    }

    record Capture(String id, String status, long amountCents, long feeCents, Instant createTime) {
    }

    record SubscriptionTransaction(String id, String status, long grossCents, long feeCents, Instant time) {
    }

    record Refund(String id, String status, long amountCents) {
    }

    // ---- Billing catalog (Catalog Products v1, Billing Plans v1) ----

    record Product(String id, String name) {
    }

    record Plan(String id, String productId, String name, String status) {
    }

    /** A monthly plan that bills {@code unitPriceCents} times the subscription quantity, with no end date. */
    record CreatePlan(String productId, String name, String description, long unitPriceCents) {
    }

    List<Product> listProducts();

    Product createProduct(String name, String description, String requestId);

    List<Plan> listPlans(String productId);

    Plan createPlan(CreatePlan request, String requestId);

    // ---- Payouts (Payouts v1) ----

    /**
     * One payout item in its own batch. {@code transactionStatus} is PayPal's word (SUCCESS, PENDING, FAILED,
     * UNCLAIMED, RETURNED, BLOCKED, ONHOLD, REFUNDED, REVERSED) and empty while the batch has no item yet.
     * {@code batchStatus} is NEW, PENDING, PROCESSING, SUCCESS, DENIED or CANCELED.
     */
    record PayoutItem(String batchId, String batchStatus, Optional<String> itemId, Optional<String> transactionStatus,
                      Optional<String> transactionId, long feeCents, Optional<String> errorName, Optional<String> errorMessage) {
    }

    /** {@code senderBatchId} is PayPal's own duplicate guard; {@code recipientType} and {@code wallet} are sent as is. */
    record CreatePayout(String senderBatchId, String senderItemId, RecipientType recipientType, String receiver,
                        PayoutWallet wallet, long amountCents, String note) {
    }

    PayoutItem createPayout(CreatePayout request, String requestId);

    PayoutItem getPayoutItem(String payoutItemId);

    /** The batch header plus its first item; Seed sends one item per batch. */
    PayoutItem getPayoutBatch(String payoutBatchId);

    // ---- Transaction Search (Reporting v1) ----

    /**
     * Reports what the credentials' account can see. {@code onBehalfOfPayerId} is the third-party (partner)
     * path: the call then reads that merchant's account through a {@code PayPal-Auth-Assertion}, and only
     * works when PayPal granted Seed that permission.
     */
    record SearchTransactions(Instant from, Instant to, Optional<String> onBehalfOfPayerId) {
    }

    /** Amounts are signed: credits positive, fees and refunds negative, as PayPal reports them. */
    record SearchedTransaction(String transactionId, String eventCode, String status, Instant initiatedAt, long amountCents,
                               long feeCents, Optional<String> referenceTransactionId, Optional<String> payerAccountId,
                               boolean payerIsPerson, String subject) {
    }

    /** One window of at most 31 days; pages are followed internally. */
    List<SearchedTransaction> searchTransactions(SearchTransactions query);

    Subscription createSubscription(CreateSubscription request, String requestId);

    /** Same as {@link #createSubscription} on an explicit plan (the savings plan). */
    Subscription createSubscriptionOnPlan(String planId, CreateSubscription request, String requestId);

    Subscription getSubscription(String subscriptionId);

    Revision reviseQuantity(String subscriptionId, long quantity, String requestId);

    void suspend(String subscriptionId, String reason, String requestId);

    void activate(String subscriptionId, String reason, String requestId);

    void cancel(String subscriptionId, String reason, String requestId);

    /** Captures an outstanding balance on a subscription (the single retry). */
    Capture captureOutstanding(String subscriptionId, long amountCents, String note, String requestId);

    List<SubscriptionTransaction> subscriptionTransactions(String subscriptionId, Instant from, Instant to);

    Order createOrder(CreateOrder request, String requestId);

    Capture captureOrder(String orderId, String requestId);

    Refund refundCapture(String captureId, long amountCents, String invoiceId, String requestId);

    Refund refundSale(String saleId, long amountCents, String requestId);

    /** PayPal's verify-webhook-signature check over the transmission headers and raw body. */
    boolean verifyWebhook(Map<String, String> transmissionHeaders, String rawBody);
}
