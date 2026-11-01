# ADR 0013: PayPal Payouts, the Billing Catalog, Transaction Search and Venmo

## Status
Proposed. Vendor details marked "unverified" are listed at the end as sandbox checks.

## Context
Until now the `payments` module covered one direction: collecting loan repayments (subscriptions, orders, captures, refunds; [ADR 0005](0005-paypal-mandate-desired-state-reconciliation.md)). Four things were missing on the PayPal side:

1. **Money out.** A microloan has to reach the member. Nothing in `payments` could send money.
2. **Savings deposits.** Goal savings needs a recurring deposit. There was no savings plan and no way to create one, and `scripts/paypal-bootstrap.sh` was a placeholder.
3. **Income evidence.** Fair loan check (F3) needs the member's weekly inflows. Today they are seeded by hand.
4. **Venmo.** Members who live in Venmo should be able to receive a loan and, where PayPal allows it, save from Venmo.

The constraints from earlier decisions still hold: only `payments` calls PayPal (INV-10), every PayPal write carries an idempotency key, money is `long` cents, and the agent has no write path ([ADR 0010](0010-llm-gateway-agent-runtime-and-paypal-toolkit-boundary.md) keeps PayPal's agent toolkit out of the money path). The `savings`, `lending` and `underwriting` modules will call what is built here in a later step; this ADR fixes the contract they call.

## Decision

### 1. One billing catalog, two `$1 x quantity` plans
`PaypalCatalogService` makes sure a PayPal product ("PayPal Seed") and two monthly plans exist:

| Plan name | Used for |
| --- | --- |
| `seed-savings-autodeposit-usd-1` | Savings deposits (F1) |
| `seed-loan-autopay-usd-1` | Loan autopay (F4); the plan [04 §2](../features/f4-repayment-autopay/04-paypal-integration.md) already describes |

Both are `MONTH`, no end date (`total_cycles: 0`), `quantity_supported: true`, a fixed price of `$1.00`, and `auto_bill_outstanding: false`. The subscription **quantity is the amount in whole dollars**, exactly as `createSubscription` and `reviseQuantity` already use it. A savings deposit of `$25` is quantity 25. This keeps one mental model for both plans and means a savings subscription must be a whole number of dollars of at least `$1`; `StartSavingsSubscription` refuses anything else. (A `$0.01` plan would allow cents but would put quantity in the thousands and break the symmetry with the loan plan.)

The service is idempotent. For each object it tries, in order: the id in configuration, the id Seed recorded in `payments.paypal_catalog_entry`, a lookup by name at PayPal (product by name, plan by name and `ACTIVE`), and only then a create. Creates run through `PayPalCommandExecutor` with a stable key (`catalog-product:<name>`, `catalog-plan:<productId>:<name>`), which is sent as `PayPal-Request-Id`, so two racing bootstraps still end with one product and one plan each. Plans are cached under `<productId>/<planName>`, so a changed product never picks up an old plan.

`POST /internal/paypal/catalog/bootstrap` (service token) exposes it; `scripts/paypal-bootstrap.sh` calls it and prints the ids and the variable names to set. `PaymentsFacade.ensureSavingsPlan()` returns `PaypalCatalog(productId, savingsPlanId, loanPlanId)`. The draft contract is `contracts/openapi/pending/payments.yaml`.

### 2. Savings subscriptions
`PaymentsFacade.createSavingsSubscription(StartSavingsSubscription)` creates a subscription on the savings plan with `quantity = monthlyCents / 100`, `custom_id = goal:<goalId>`, and no `start_time` (billing starts when the member approves at the returned `approveUrl`). It is idempotent per `(goalId, requestId)`: the same pair returns the same subscription, and the same pair with another member or amount is `409 idempotency-key-reused`. `PayPal-Request-Id` is `ss:<goalId>:<first 16 hex of sha256(requestId)>`, short enough for PayPal's 108-character limit whatever the caller sends.

Credits to the goal are not done here. The existing `PayPalWebhookController` already routes `PAYMENT.SALE.COMPLETED` and `BILLING.SUBSCRIPTION.CANCELLED` of a subscription to `SavingsFacade`. The normaliser now recognises savings subscriptions (by the recorded row, or `custom_id` starting `goal:`) and ignores their payment events instead of parking them forever waiting for a loan mandate.

### 3. Payouts
`PaymentsFacade.sendPayout(SendPayout)` and `payoutOf(referenceId)` use the Payouts API: `POST /v1/payments/payouts`, one item per batch, `sender_batch_id = "seed:" + referenceId`, `sender_item_id = referenceId`, `recipient_type` `PAYPAL_ID`, `EMAIL` or `PHONE`, `recipient_wallet` `PAYPAL` or `VENMO`, and a note. The `seed:` prefix lets the webhook handler ignore payouts sent from the same PayPal account by anything else.

**Idempotency.** The caller's `referenceId` is the key everywhere: it names the `payments.payout` row (unique), seeds `sender_batch_id` and the `PayPal-Request-Id` (`payout:<referenceId>`), and so a replay can never pay twice. The same reference with another amount, recipient type, recipient or wallet is `409 payout-reference-reused`. A transient PayPal failure rolls the whole send back (no row, no command), and a retry with the same reference sends the same request id. A PayPal refusal (a 4xx that is not transient) commits the payout as `FAILED` with PayPal's issue as the reason and publishes `PayoutFailed`; the reference is spent and a new attempt needs a new reference.

**State.** `payments.payout` holds the current state and `payments.payout_transition` every change. The states are `REQUESTED`, `PENDING`, `SUCCEEDED`, `FAILED`, `RETURNED`, `BLOCKED`, `UNCLAIMED`. The legal moves live in one pure class (`PayoutStateMachine`) and are enforced again by the database (`payments.guard_payout`): a payout is never deleted, its request fields never change, `FAILED`, `BLOCKED` and `RETURNED` are final, `SUCCEEDED` can only become `RETURNED`, and `UNCLAIMED` can become `SUCCEEDED`, `FAILED` or `RETURNED`. `payout_transition` rejects update and delete.

**Events.** A report from PayPal (the create response, an item webhook, a batch webhook) is applied only if it moves the payout forward, and only then is a transition recorded and an event published, so every outcome produces exactly one event however many reports carry it:

| Outcome | Event |
| --- | --- |
| `SUCCEEDED` | `PayoutSucceeded` (transaction id, fee) |
| `FAILED`, `BLOCKED` | `PayoutFailed` with a reason code, `terminal = true` |
| `UNCLAIMED` | `PayoutFailed`, reason `UNCLAIMED`, `terminal = false`; a later `PayoutSucceeded` (claimed) or `PayoutReturned` (reason `UNCLAIMED_EXPIRED`) follows |
| `RETURNED` | `PayoutReturned` |

**Webhooks.** `PAYMENT.PAYOUTS-ITEM.SUCCEEDED`, `.FAILED`, `.BLOCKED`, `.RETURNED` and `.UNCLAIMED` set the state directly; `.DENIED` and `.CANCELED` count as `FAILED`. `PAYMENT.PAYOUTSBATCH.DENIED` fails the payout (reason `BATCH_DENIED`). `PAYMENT.PAYOUTSBATCH.SUCCESS` does **not** mean the item succeeded (PayPal reports batch and item separately), so the handler reads the batch back (`GET /v1/payments/payouts/{id}`) and applies the item's own status. Delivery dedupe is the existing inbox (`paypal_event_id`); a duplicate event id is acknowledged and ignored. A report for a payout Seed has not committed yet is parked and woken when the payout row is created, exactly like a refund that arrives before its capture.

The create response normally carries only the batch header. `createPayout` therefore reads the batch once to learn the item id and status; if that read fails, the payout still stands and the item id arrives with the first webhook.

**Money.** PayPal's decimal strings become cents in one place, `RestPayPalGateway.cents(...)`, and cents become strings through `Money.toDecimalString()`.

### 4. Venmo
- **Payouts to Venmo** need no new endpoint: `recipient_wallet: VENMO`, with a phone number or email as the recipient. The wallet type is `PaymentsDtos.PayoutWallet`. `lending` has its own `WalletType` inside its `domain` package, which `payments` may not import, and `lending` already depends on `payments`, so reusing it would create a cycle. `payments.api.dto` is the shared definition; `lending` maps its enum to it when it calls `sendPayout`.
- **Saving from Venmo.** PayPal Subscriptions cannot be funded with Venmo. A Venmo member can only make **one-off top-ups through Orders v2**, with `payment_source.venmo`. `PaymentsFacade.createSavingsTopUp` creates that order (`custom_id = goal:<goalId>:TOPUP`) and `captureSavingsTopUp` captures it; the capture is announced once as `SavingsTopUpCaptured` whether the capture call or `PAYMENT.CAPTURE.COMPLETED` reports it first (the capture id is attached to the order row once). Refunds of top-ups are ignored by the loan funnel. Venmo checkout runs through PayPal's JS SDK with Venmo enabled, mostly on mobile, so the web side must offer the Venmo button; the backend only creates and captures the order. Venmo is US and USD only.
- A recurring Venmo deposit therefore does not exist. The product answer is a reminder plus a one-tap top-up, not an autopay.

### 5. Transaction Search
`PayPalGateway.searchTransactions` calls `GET /v1/reporting/transactions` with `fields=transaction_info,payer_info`, `page_size=100` and follows the pages. One call covers at most 31 days (PayPal's limit; the adapter refuses more), so `IncomeService` cuts a range into consecutive windows, and a line on a window edge is counted once. `PaymentsFacade.incomeTransactions(accountReference, from, to)` returns `IncomeCredit` rows: transaction id, business date, gross, fee, refunded, net, a counterparty kind and the subject. The netting follows how PayPal reports:

- **Income** is a successful (`S`) line with a positive amount and an event code in the `T00xx` family (payments received). Funding (`T03xx`), withdrawals (`T04xx`), fees (`T01xx`), conversions (`T02xx`) and the member's own spending are not income.
- **Fees** are the negative `fee_amount` on the same line.
- **Refunds, reversals and chargebacks** (`T11xx`, `T12xx`) are separate negative lines that point at the payment through `paypal_reference_id`; they reduce that payment's net. A refund whose payment lies outside the range cannot be matched and is left out, so callers should read wide windows.
- `net = max(0, gross - fee - refunded)`. Non-USD lines are skipped.

The feature is behind `seed.paypal.transaction-search.enabled` (default `false`); while off, the facade refuses with `412 transaction-search-disabled`.

**The real limit.** Transaction Search reports the account that the REST app's credentials belong to. It cannot read a member's own PayPal wallet just because the member consented in our app. Reading a third party's account needs PayPal partner permissions and a `PayPal-Auth-Assertion` header naming that account; the adapter can send it (`seed.paypal.transaction-search.on-behalf=true`), but PayPal only honours it for partners with that permission granted by the account holder. Consequences:

- In the **sandbox demo**, Seed reads the program account, which holds the seeded sandbox payments; the account reference is ignored unless `on-behalf` is on.
- The **product pitch** (a member's wallet history as evidence) assumes first-party access, which Seed has only if it is built into PayPal itself. A standalone Seed would need the partner programme or a member-uploaded statement.
- Data can lag by up to about three hours, and history is limited to three years; both are in the adapter's and service's guards.

### 6. Faithful to the existing style
New PayPal writes go through `PayPalCommandExecutor` (the `paypal_command` kind list is extended in migration V3); reads go straight to the gateway. The adapter keeps the existing retry rules: a `401` refreshes the token once, `429` and `5xx` are retryable, a read timeout is ambiguous (replay with the same request id), `404`, `409` and `422` are stale-state, other `4xx` are final. The read timeout became configurable so the timeout path can be tested.

## Settings
| Key | Default | Meaning |
| --- | --- | --- |
| `seed.paypal.product-id` | empty | Use this product instead of finding or creating one |
| `seed.paypal.product-name` | `PayPal Seed` | Name the product is found or created by |
| `seed.paypal.savings-plan-id` | empty | Use this plan for savings deposits |
| `seed.paypal.loan-plan-id` | empty (existing) | Use this plan for loan autopay |
| `seed.paypal.read-timeout-ms` | `20000` | HTTP read timeout toward PayPal |
| `seed.paypal.transaction-search.enabled` | `false` | Allow `incomeTransactions` |
| `seed.paypal.transaction-search.on-behalf` | `false` | Send `PayPal-Auth-Assertion` for a member's payer id (partner permission required) |

Environment names: `SEED_PAYPAL_PRODUCT_ID`, `SEED_PAYPAL_SAVINGS_PLAN_ID` (Spring maps these without a change to `application.yml`), and the existing `PAYPAL_LOAN_PLAN_ID`.

## Alternatives considered
- **A `$0.01` savings plan.** Allows any amount in cents, but quantity would be in the thousands and differ from the loan plan. Rejected for the symmetry.
- **Batch payouts.** One batch per disbursement keeps the idempotency story and the item-to-event mapping trivial; batching saves nothing at program scale.
- **Treating `PAYOUTSBATCH.SUCCESS` as success.** Would announce money delivered while an item is still pending, blocked or unclaimed. Reading the item back costs one GET.
- **Polling for payout status instead of webhooks.** Kept as a fallback only: `getPayoutItem` and `getPayoutBatch` exist on the gateway for a later sweeper, and the state machine already accepts a report from any source.
- **A Venmo-funded subscription.** Not offered by PayPal.

## Consequences
- Disbursement, savings deposits and income reads each have a tested, idempotent facade method, and the later wiring in `lending`, `savings` and `underwriting` only calls them.
- `payments` already depends on `savings` (the webhook controller calls `SavingsFacade`). Calling `PaymentsFacade` from `savings` would be a module cycle. The wiring step has to invert that dependency (for example, `savings` listens to a payments event, or the webhook routing moves out of `payments`) before `savings` can call `createSavingsSubscription`.
- Savings and loan subscriptions share one PayPal product and webhook endpoint; the normaliser tells them apart by the recorded savings row and the `goal:` / `loan:` custom id.
- Income evidence is only as good as PayPal's permissions (see above) and arrives with a lag of up to three hours.

## Sandbox checks (unverified)
| # | Question | How |
| --- | --- | --- |
| P1 | Is the Payouts permission enabled on the sandbox app, and does the create response carry items? | `POST /v1/payments/payouts` once; compare with the adapter's follow-up read |
| P2 | Exact `sender_item_id` and `sender_batch_id` length limits | Send 56-character references |
| P3 | Does a Venmo payout work in the sandbox, and with which recipient type? | `recipient_wallet: VENMO` with phone and email |
| P4 | Webhook payload shape of `PAYMENT.PAYOUTS-ITEM.*` (are `sender_batch_id` and `payout_item.sender_item_id` both present?) | Record from the Postman simulator |
| P5 | `category` accepted for the product (`SOFTWARE` is used) | Create the product |
| P6 | Is `PayPal-Auth-Assertion` honoured for any sandbox pair, and what `transaction_event_code` do sandbox payouts show? | Search a seeded sandbox account |
