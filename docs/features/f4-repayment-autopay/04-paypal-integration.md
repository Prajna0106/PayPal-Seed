# F4 — PayPal Integration

All PayPal **write** calls go through the `payments` executor (INV-10). Every call is a row in `payments.command` with a deterministic `PayPal-Request-Id`. Endpoint shapes follow PayPal's REST docs. Confirm each one in spike week with the PayPal AI Toolkit and the APIMatic Context Plugin (see §10).

## 1. Principles

1. **The schedule is the truth.** PayPal objects (subscription, invoice, order) are collection channels. They are reconciled toward the schedule, never read as the plan.
2. **Dedupe by money identity.** The PayPal transaction id (sale id or capture id) is the key for a repayment. The webhook event id only dedupes delivery.
3. **Read before you write again.** After a timeout, the executor first re-observes (GET), then replays with the *same* request id.
4. **No surprise charges.** Overdue amounts are never added to the next autopay charge. `auto_bill_outstanding = false`. There is at most one automatic retry.

## 2. The loan-autopay plan

Created once by `scripts/paypal-bootstrap.sh`. The script is idempotent: it looks the product and plan up by name before creating them, and writes their ids to the Render environment group.

```http
POST /v1/catalogs/products
{ "name": "PayPal Seed loan repayment", "type": "SERVICE", "category": "SOFTWARE" }

POST /v1/billing/plans
{
  "product_id": "PROD-…", "name": "seed-loan-autopay-usd-1", "status": "ACTIVE",
  "quantity_supported": true,
  "billing_cycles": [{
    "frequency": { "interval_unit": "MONTH", "interval_count": 1 },
    "tenure_type": "REGULAR", "sequence": 1, "total_cycles": 0,
    "pricing_scheme": { "fixed_price": { "value": "1.00", "currency_code": "USD" } }
  }],
  "payment_preferences": {
    "auto_bill_outstanding": false,
    "setup_fee_failure_action": "CONTINUE",
    "payment_failure_threshold": 3
  }
}
```

- **`$1 × quantity`.** Instalments are whole dollars ([03 §1](03-algorithms.md#1-money-and-dates)), so quantity = instalment in dollars charges exactly.
- **`total_cycles: 0` (no fixed end).** Schedule versions change the number of instalments. We cancel the subscription when the loan is repaid rather than trusting PayPal's cycle count.
- **`auto_bill_outstanding: false`.** PayPal must not add a failed month to the next one. Overdue money is collected by our single retry (§3.4) or by a Payment Link.
- This plan is **separate from the savings plan** (F1), so reports and webhooks stay unambiguous.

## 3. Subscription lifecycle (autopay)

### 3.1 Create and approve

```http
POST /v1/billing/subscriptions
PayPal-Request-Id: cmd-{commandId}
{
  "plan_id": "P-…", "quantity": "150",
  "custom_id": "loan:{loanId}",
  "start_time": "{firstDueDate}T10:00:00-08:00 → converted to UTC",
  "application_context": {
    "brand_name": "PayPal Seed", "user_action": "SUBSCRIBE_NOW", "shipping_preference": "NO_SHIPPING",
    "return_url": "https://member…/loans/{loanId}/autopay/return",
    "cancel_url": "https://member…/loans/{loanId}/autopay/cancelled"
  }
}
```
The response carries `links[rel=approve]`, which is stored on the mandate (`APPROVAL_PENDING`). The member approves in the PayPal or Venmo popup. Two paths can confirm, and both are safe to race: `BILLING.SUBSCRIPTION.ACTIVATED`, or `POST /autopay/confirm` from the return URL (which does a GET on the subscription). Whichever comes first moves the mandate to `ACTIVE`; the other is a no-op.

An approval that is still pending after 3 hours expires. A new `POST /autopay` creates a fresh subscription.

### 3.2 Observe
`GET /v1/billing/subscriptions/{id}` gives `status`, `quantity`, `billing_info.next_billing_time`, `billing_info.outstanding_balance` and `billing_info.failed_payments_count`. These are stored as `observed_*` on the mandate.

### 3.3 Commands issued by the reconciler

| Command | Endpoint | When |
| --- | --- | --- |
| Revise quantity | `POST /v1/billing/subscriptions/{id}/revise {"quantity": "143"}` | Next instalment amount differs (remainder dollar, reamortise) |
| Suspend | `POST /v1/billing/subscriptions/{id}/suspend {"reason": "Seed: agreed pause"}` | No instalment due this cycle (pause, moved date, already paid) |
| Activate | `POST /v1/billing/subscriptions/{id}/activate` | Cycle with money due again |
| Cancel | `POST /v1/billing/subscriptions/{id}/cancel {"reason": "Loan repaid"}` | Repaid, mode changed, loan closed |

If a revise response contains an `approve` link (the payer must consent), the reconciler marks the desired state as unattainable, notifies the member with the link, and falls back to a Payment Link for that cycle. Spike S3 decides whether this can happen. If it can, the policy switches to `remainderPlacement = FRONT` so quantities only fall ([03 §2](03-algorithms.md#2-generate-equal-whole-dollar-instalments)).

### 3.4 Failed payment and the single retry

`BILLING.SUBSCRIPTION.PAYMENT.FAILED` → `PaymentFailed`. Lending plans one retry ([03 §8](03-algorithms.md#8-income-aware-retry-of-a-failed-autopay)). On the retry date:

```http
POST /v1/billing/subscriptions/{id}/capture
PayPal-Request-Id: retry-{loanId}-v{ver}-s{seq}-1
{ "note": "Seed instalment 3 retry", "capture_type": "OUTSTANDING_BALANCE",
  "amount": { "currency_code": "USD", "value": "150.00" } }
```
The result arrives as `PAYMENT.SALE.COMPLETED` or another failure, through the same funnel.

### 3.5 Member cancels in PayPal
`BILLING.SUBSCRIPTION.CANCELLED` not caused by one of our commands (no matching `payments.command`) → mode = `INVOICE`, `AutopayCancelled(cause=MEMBER)`, and a friendly SMS. This is never treated as a default.

## 4. Invoice mode: just-in-time invoices

COB creates one invoice per instalment 7 days before it is due, for the instalment's **remaining** amount under the **current** version. A recurring invoice series is not used, because it cannot follow a rescheduled plan.

```http
POST /v2/invoicing/invoices
PayPal-Request-Id: inv-{loanId}-v{ver}-s{seq}
{
  "detail": { "invoice_number": "SEED-{loanShort}-V{ver}-S{seq}", "reference": "loan:{loanId}",
              "currency_code": "USD", "payment_term": { "due_date": "{dueDate}" },
              "note": "Instalment 3 of 8. No fees, ever." },
  "primary_recipients": [{ "billing_info": { "email_address": "{member email}" } }],
  "items": [{ "name": "Seed loan instalment 3 of 8", "quantity": "1",
              "unit_amount": { "currency_code": "USD", "value": "150.00" } }],
  "configuration": { "allow_tip": false,
                     "partial_payment": { "allow_partial_payment": true,
                                          "minimum_amount_due": { "currency_code": "USD", "value": "1.00" } } }
}
POST /v2/invoicing/invoices/{id}/send
```

- `invoice_number` is unique at PayPal, which gives a second, natural idempotency guard.
- On `ScheduleChanged`, open invoices whose (seq, amount, due date) changed are cancelled (`POST /v2/invoicing/invoices/{id}/cancel`) and reissued under the new version number.
- `INVOICING.INVOICE.PAID` → the transaction ids in `payments.transactions[]` → `PaymentCaptured`. If the same money also arrives as `PAYMENT.CAPTURE.COMPLETED`, `repayment.paypal_txn_id UNIQUE` makes it a no-op.

## 5. One-off payments: Orders v2 and Payment Links

```http
POST /v2/checkout/orders
PayPal-Request-Id: ord-{commandId}
{ "intent": "CAPTURE",
  "purchase_units": [{
     "reference_id": "{loanId}", "custom_id": "loan:{loanId}:CATCH_UP",
     "invoice_id": "SEED-PAY-{commandId}",
     "description": "Seed loan payment",
     "amount": { "currency_code": "USD", "value": "150.00" } }],
  "payment_source": { "paypal": { "experience_context": {
     "brand_name": "PayPal Seed", "shipping_preference": "NO_SHIPPING", "user_action": "PAY_NOW" } } } }

POST /v2/checkout/orders/{id}/capture        PayPal-Request-Id: cap-{orderId}
```

- Venmo is offered through the JS SDK v6 Venmo button on the same order.
- `invoice_id` uniqueness stops PayPal from capturing the same purpose twice.
- **Payment Links** (`POST /v1/checkout/payment-resources`) are created for SMS catch-up with a fixed amount equal to the overdue amount. Spike S7 confirms which reference field reaches the capture webhook, so it can be mapped back to the loan.

## 6. Refunds

| Case | Endpoint |
| --- | --- |
| Overpayment refund (order capture) | `POST /v2/payments/captures/{captureId}/refund {amount, invoice_id: "SEED-RF-{cmd}", note_to_payer}` |
| Overpayment refund (subscription sale) | `POST /v1/payments/sale/{saleId}/refund {amount}` |
| Auto-refund after closure | Same, against the late-arriving transaction itself |

The target is the most recent transaction from that member for that loan with enough refundable amount. A refund is split across transactions only when it has to be. A refund never goes to any other destination.

## 7. Webhooks → normalised commands

Ingress verifies the signature (`POST /v1/notifications/verify-webhook-signature`, or local certificate verification), inserts into `webhook_inbox (paypal_event_id UNIQUE)`, and returns 200 within 300 ms. The worker normalises:

| PayPal event | Resolve loan by | Normalised | Lending effect |
| --- | --- | --- | --- |
| `BILLING.SUBSCRIPTION.ACTIVATED` | `custom_id`, subscription id | `MandateActivated` | `collection_mode = AUTOPAY` |
| `BILLING.SUBSCRIPTION.SUSPENDED` / `RE-ACTIVATED` / `UPDATED` | subscription id | `MandateObserved` | Reconciler compares |
| `BILLING.SUBSCRIPTION.CANCELLED` / `EXPIRED` | subscription id | `MandateEnded(cause)` | Mode → INVOICE if not caused by us |
| `BILLING.SUBSCRIPTION.PAYMENT.FAILED` | subscription id | `PaymentFailed` | Attempt FAILED, retry planned, events |
| `PAYMENT.SALE.COMPLETED` | `billing_agreement_id` | `PaymentCaptured` | `RecordRepayment` |
| `PAYMENT.SALE.PENDING` | `billing_agreement_id` | `PaymentPending` | `pendingCover`, reminders held |
| `PAYMENT.SALE.REFUNDED` / `REVERSED` | parent sale id | `PaymentReversed` | `RecordAdjustment` |
| `PAYMENT.CAPTURE.COMPLETED` | `custom_id` / order id / invoice id | `PaymentCaptured` | `RecordRepayment` |
| `PAYMENT.CAPTURE.PENDING` / `DECLINED` | same | `PaymentPending` / `PaymentFailed` | |
| `PAYMENT.CAPTURE.REFUNDED` / `REVERSED` | parent capture | `PaymentReversed` | `RecordAdjustment` |
| `INVOICING.INVOICE.PAID` | invoice id | `PaymentCaptured` per transaction | `RecordRepayment` |
| `INVOICING.INVOICE.CANCELLED` | invoice id | `InvoiceObserved` | none |
| `CUSTOMER.DISPUTE.CREATED` / `RESOLVED` | disputed transaction id | to F10; a lost dispute → `PaymentReversed` | `RecordAdjustment` on loss |

**Out of order.** If the loan or mandate cannot be resolved yet (for example, a sale before the activation is processed), the event is parked in `payments.parked_event` with `wait_for = subscription:{id}`. It is replayed when the parent commits, and raises an alert after 15 minutes.

**Fee and gross.** `PAYMENT.SALE.COMPLETED.resource.transaction_fee` and `PAYMENT.CAPTURE.COMPLETED.resource.seller_receivable_breakdown.paypal_fee` supply `fee_cents`. The ledger posts gross, fee and net ([02 §5](02-design.md#5-ledger-postings-owned-by-ledger-requested-by-lending)).

## 8. Idempotency summary

| Layer | Key | Guarantees |
| --- | --- | --- |
| Our API | `Idempotency-Key` header + request hash, stored in `idempotency_record` (Key Value cache in front) | Retried client writes return the first response |
| PayPal writes | `PayPal-Request-Id = {kind}-{deterministic id}` | PayPal returns the original result on replay |
| PayPal natural keys | `invoice_number`, order `invoice_id` | PayPal refuses duplicates |
| Webhook delivery | `webhook_inbox.paypal_event_id UNIQUE` | Each event is processed once |
| Money | `repayment.paypal_txn_id UNIQUE`, `journal_entry.source_key UNIQUE` | One repayment and one posting per PayPal transaction |
| COB effects | timer deleted in the effect's transaction; command keys `cob:{D}:{loan}:{kind}:{ref}` | Exactly-once effect per business date |

## 9. Errors and retries in the executor

| PayPal response | Class | Action |
| --- | --- | --- |
| Timeout, connection reset | Ambiguous | GET the resource (or replay with the same request id), then decide |
| 429, 500, 502, 503, 504 | Retryable | Exponential backoff with full jitter: base 2 s, cap 5 min, 8 attempts; circuit breaker per endpoint family (opens at 50% failures over 20 calls) |
| 401 | Auth | Refresh the OAuth token once (tokens cached per instance, renewed 60 s before expiry) |
| 404 on a subscription or invoice | State | Re-observe; the reconciler re-plans |
| 422 (for example `SUBSCRIPTION_STATUS_INVALID`) | State | Re-observe and re-plan; never blind-retry |
| 400 or other 4xx | Final | `FAILED_FINAL` → desk dead-letter with replay; alert |

## 10. Sandbox spikes (day 1)

| # | Question | How to test | Fallback if the answer is bad |
| --- | --- | --- | --- |
| S1 | Does `quantity_supported` with a $1 plan charge `quantity × $1` exactly, up to at least 5,000? | Create a plan and subscribe with quantity 150 and 4,999 | One plan per $25 amount band, plus a Payment Link for the difference |
| S2 | When, in UTC, does the first charge happen relative to `start_time`? | Subscribe with start_time = now + 5 min | Widen the reconciler's match tolerance; grace covers it |
| S3 | Does `revise` that *raises* quantity return an approve link (payer consent)? Is the new quantity effective from the next cycle? | Revise 150 → 151 and 150 → 149 | `remainderPlacement = FRONT` (quantities only fall) |
| S4 | After `suspend` then `activate`, is the skipped cycle billed? What is the new `next_billing_time`? | Suspend before a cycle, activate after | Use Payment Links for paused cycles and keep the subscription active only for normal months |
| S5 | Does `capture` with `OUTSTANDING_BALANCE` work after a failed cycle when `auto_bill_outstanding = false`? | Force a failure (sandbox buyer with no funding), then capture | Retry by Payment Link sent by SMS |
| S6 | Exact payloads for `PAYMENT.SALE.COMPLETED` (`billing_agreement_id`, fee) and `INVOICING.INVOICE.PAID` (transaction ids); is invoice payment also sent as `PAYMENT.CAPTURE.COMPLETED`? | Webhook simulator plus real sandbox flows; save fixtures to `postman/fixtures/webhooks/` | The normaliser handles both; txn-id dedupe makes it safe either way |
| S7 | Which reference from a Payment Link reaches the capture webhook? | Create a link, pay it, inspect the webhook | Map by link id stored at creation |
| S8 | How to force a subscription payment failure in sandbox | Negative testing (`PayPal-Mock-Response`) or a buyer account with no funding source | Postman Simulator replays the recorded failure webhook (signed in test mode) |
| S9 | Is Vault v3 (saved PayPal or Venmo wallet, merchant-initiated orders) enabled on the sandbox merchant? | `POST /v3/vault/setup-tokens`, then an MIT order | Not needed if S3 and S4 pass; preferred if they fail (see ADR 0005) |

Record each answer in this file (append the results under the table) before T7 starts.

## 11. Demo time vs PayPal time

PayPal sandbox subscriptions bill in **real** time, but the demo clock moves months in seconds. The `CollectionChannel` port in `payments` has the production behaviour (PayPal bills on its own cycle) and a `demo` profile behaviour. In the demo profile, the COB `DUE` timer creates a **real sandbox charge** on the demo due date:

1. Vault MIT order, if S9 passes, or
2. Subscription `capture` of outstanding, if S5 passes and a cycle has failed, or
3. A sandbox buyer approving a pre-created order in a scripted Playwright step.

Every demo-triggered charge has `custom_id` suffix `:demo` and is labelled "demo-triggered" on screen, as plan §12 requires.

## 12. Nightly reconciliation (F4 part of plan §10 7a)

`GET /v1/reporting/transactions?start_date=…&end_date=…&fields=all&page_size=500` for the program account (data can lag up to about 3 hours, so the window is D−2 to D−1). For every transaction with `custom_field` starting `loan:` or a subscription id in `payments.collection_mandate`:
- Present at PayPal, missing in `lending.repayment` → ingest through the normal funnel with `source = RECONCILER` and alert.
- Present in `lending.repayment`, missing at PayPal → `ReconciliationDrift` (Sev-2), never auto-deleted.
- Amount or fee mismatch → `ReconciliationDrift`.
