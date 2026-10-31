# F4 — Technical Design

Read [01-requirements.md](01-requirements.md) first. The algorithms live in [03-algorithms.md](03-algorithms.md), and PayPal specifics in [04-paypal-integration.md](04-paypal-integration.md).

## 1. Ownership across modules

This follows the dependency rules in [STRUCTURE.md §3.2](../../STRUCTURE.md). No module reads another module's schema.

| Concern | Owner | How the others reach it |
| --- | --- | --- |
| Schedule versions, instalments, repayments, allocation, aging, timers, COB | `lending` | `lending.api.LendingFacade` (queries) and events |
| Pure maths: generate, allocate, age, timers, reschedule | `engines/schedule` | Plain Java library, no Spring, no IO |
| Policy values and approval tiers | `engines/rules` | `RepaymentPolicy` by version |
| PayPal subscription, invoice, order, refund; webhook ingress; executor; mandate reconciler | `payments` | `payments.api` commands; results come back as events |
| Journal postings and balances | `ledger` | `ledger.api.post(...)`, called in-process inside the lending transaction (ADR 0002) |
| SMS, in-app notifications, Zapier | `engagement` | Consumes lending events |
| Desk grids, roll rates, KPIs | `desk-query` | Consumes lending events into its own projections |
| Read-only agent tools | `agent-gateway` | Calls `LendingFacade` queries |
| Due-day income profile | `underwriting` (F3) | `UnderwritingFacade.incomeTimingProfile(memberId)` returns a summary only |

```text
                  ┌──────────── payments ─────────────┐
 PayPal ─webhook─►│ ingress → inbox → normaliser       │──PaymentCaptured / PaymentFailed / PaymentRefunded──┐
        ◄─REST────│ executor (only writer) ◄ commands  │◄──EnsureMandate / CreateInvoice / RetryCapture ───┐ │
                  │ mandate reconciler (desired vs actual)                                           │ │
                  └────────────────────────────────────┘                                            │ │
                                                                                                    │ ▼
                  ┌──────────── lending ──────────────────────────────────────────────────────────────────┐
                  │ RecordRepayment use case: lock loan → insert repayment → ledger.post → fold allocation │
                  │ → age → rebuild timers → outbox   (ONE transaction)                                   │
                  │ COB: fire due timers per shard → age → events → commands                              │
                  │ engines/schedule (pure)                                                               │
                  └──────┬───────────────────────────────────────────────────────────────────────────────┘
                         │ events (outbox)
            ┌────────────┼──────────────┬───────────────┬──────────────┐
            ▼            ▼              ▼               ▼              ▼
       engagement    desk-query      hardship (F5)   credit-record   fraud (F10)
       (Zapier SMS)  (AG Grid)       (Guardian)      (F6)            (Elastic)
```

## 2. Core flows

### 2.1 Disbursement → schedule → autopay

1. `payments` publishes `LoanDisbursed` (F2), which carries the payout transaction id.
2. `lending` listener: `generate(principal, n, dueDay, disbursedOn, policy)` → version 1 → insert. Rebuild timers. Emit `ScheduleGenerated`.
3. The member taps "Turn on autopay". `POST /loans/{id}/autopay` → `payments.ensureMandate(loanId, desired)` → executor creates the subscription → returns the approval link.
4. The member approves in the PayPal or Venmo popup. `BILLING.SUBSCRIPTION.ACTIVATED` (or the return-URL poll) → mandate `ACTIVE` → `AutopayActivated` → loan `collection_mode = AUTOPAY`.

### 2.2 Autopay success
`PAYMENT.SALE.COMPLETED` (`billing_agreement_id` = subscription id) → ingress verifies, stores in the inbox, returns 200 → worker normalises it to `PaymentCaptured{txnId, gross, fee, payer, at, ref: mandate}` → lending `RecordRepayment` (one transaction) → events `RepaymentReceived`, `RepaymentAllocated`, and possibly `LateBucketChanged` or `LoanRepaid` → payments reconciler runs (for example, revises the quantity before the final instalment).

### 2.3 Autopay failure and retry
`BILLING.SUBSCRIPTION.PAYMENT.FAILED` → `PaymentFailed` → lending records a `collection_attempt` as FAILED → emits `RepaymentFailed` → engagement sends the failure SMS. Hardship (F5) receives the signal. Lending plans one retry (`03 §8`) as a timer. When the timer fires during COB → `payments.retryCapture(mandate, amount)` → `POST /v1/billing/subscriptions/{id}/capture` → a success or failure webhook comes back as usual.

### 2.4 Catch-up, early repayment or payoff
`POST /loans/{id}/payments {amountCents, purpose}` → payments creates an order (`custom_id = loan:{id}:{purpose}`) → the member approves → `POST .../payments/{orderId}/capture` (or the `PAYMENT.CAPTURE.COMPLETED` webhook, whichever is first; dedupe by transaction id) → `RecordRepayment`.

### 2.5 Refund, reversal, chargeback
`PAYMENT.SALE.REFUNDED | PAYMENT.CAPTURE.REFUNDED | *.REVERSED` → `PaymentReversed{originalTxnId, txnId, amount}` → lending `RecordAdjustment` (one transaction: adjustment row, ledger reversal, re-fold, re-age, timers, events). A `REPAID` loan reopens if the balance becomes greater than 0.

### 2.6 Close of business
See [03 §7](03-algorithms.md#7-close-of-business-cob). Render Workflows fans the shards out; each shard step is an idempotent backend job.

## 3. State machines

### 3.1 Loan (F4 part)

```text
PENDING_DISBURSEMENT ──LoanDisbursed──► ACTIVE ──outstanding=0──► REPAID
                                         │  ▲                        │
                                         │  └──reversal, balance>0───┘
                                         ├──reserve cover (F8)──► COVERED
                                         └──cooling-off return (F2)──► CANCELLED
```
The late bucket is a separate, derived dimension of `ACTIVE`. It is never a loan status.

### 3.2 Collection mandate (payments)

```text
NONE ─create─► APPROVAL_PENDING ─ACTIVATED─► ACTIVE ⇄ SUSPENDED
                    │ (expires 3h, or member abandons)  │ │
                    ▼                                   ▼ ▼
                 EXPIRED                            CANCELLED (loan repaid, mode change,
                                                    or member cancelled in PayPal)
```
Desired state is computed by lending; actual state is observed from PayPal; the reconciler moves actual toward desired ([03 §9](03-algorithms.md#9-mandate-reconciler-desired-state)).

### 3.3 Instalment (derived on read, never stored)

```text
SCHEDULED (due > today + 7)  →  DUE (within 7 days or today)  →  PAID
                                     │
                                     ├→ PARTIALLY_PAID
                                     ├→ IN_GRACE (DPD 1–5)  → LATE (DPD > 5)  → PAID (late)
PREPAID (paid ahead by the inverse-maturity waterfall)
```

### 3.4 Collection attempt
`PLANNED → REQUESTED → SUCCEEDED | FAILED | CANCELLED` (cancelled when the money arrived another way or a hardship case opened).

## 4. Data model

The implemented schema is the source of truth. Read the migrations:
- [lending V2](../../../backend/modules/lending/src/main/resources/db/migration/lending/V2__repayment_schedule.sql)
- [ledger V2](../../../backend/modules/ledger/src/main/resources/db/migration/ledger/V2__double_entry_journal.sql)
- [payments V2](../../../backend/modules/payments/src/main/resources/db/migration/payments/V2__paypal_collection.sql)
- [engagement V2](../../../backend/modules/engagement/src/main/resources/db/migration/engagement/V2__reminders.sql)

Money is `bigint` cents. Dates are business dates in `America/Los_Angeles`.

| Schema.table | Kind | Purpose and key constraints |
| --- | --- | --- |
| `lending.loan` | state | Loan and derived caches (`outstanding`, `bucket`, `dpd`). `disbursement_ref UNIQUE` makes registration idempotent. `last_closed_on` is the COB watermark (Fineract's `last_closed_business_date`). `shard` is one of 64 virtual shards. CHECKs: whole-dollar principal; REPAID implies nothing owed |
| `lending.schedule_version`, `lending.instalment` | append-only | Immutable versions (ADR 0004). A deferred constraint trigger enforces INV-1 (seq 1..n, dates never go back, sum = principal). Triggers reject UPDATE and DELETE |
| `lending.loan_transaction` | append-only | One table for all money, like Fineract's `m_loan_transaction`: RECEIPT, REVERSAL, OVERPAYMENT_REFUND. `paypal_txn_id UNIQUE` is the money identity (INV-3). Reversals point at their receipt |
| `lending.instalment_allocation`, `lending.transaction_allocation` | projection | Rebuilt by every recalculation. Includes `paid_in_advance_cents`, which reschedules need (03 §10) |
| `lending.instalment_missed`, `lending.bucket_transition` | append-only facts | INV-9; roll rates and audit |
| `lending.pending_payment` | state | Pending PayPal money holds off nudges and retries |
| `lending.collection_attempt` | state | Scheduled charge and its single retry |
| `lending.loan_timer` | state | Durable timers. A fired timer keeps its row (`fired_on`), so it is never planned twice. Partial index `(fire_on, shard) WHERE fired_on IS NULL` is the COB work queue |
| `lending.cob_run` | state | One row per business date, closed strictly in order |
| `ledger.account`, `ledger.journal_entry`, `ledger.journal_line` | append-only | `source_key UNIQUE` plus a request hash (a replay returns the entry; a reused key with different lines is refused). A deferred trigger checks that every entry balances and has at least 2 lines. UPDATE, DELETE and TRUNCATE are rejected |
| `payments.paypal_command` | log | Every PayPal write; `idempotency_key` is sent as `PayPal-Request-Id` |
| `payments.collection_mandate` | state | Autopay subscription; at most one live mandate per loan (partial unique index) |
| `payments.webhook_inbox` | queue | Verified webhooks; `paypal_event_id UNIQUE`. Claimed with `SKIP LOCKED`; PARKED until a parent arrives |
| `payments.captured_payment`, `payments.refund`, `payments.paypal_order` | registry | Money PayPal moved for a loan; resolves reversals and bounds refunds |
| `engagement.contact`, `engagement.reminder` | state, queue | Opt-out, locale and time zone; one message per fact (`dedupe_key` primary key), quiet hours applied |
| `public.event_publication` | outbox | Spring Modulith event publication registry: events commit with their state change and are retried until each listener completes |

## 5. Ledger postings (owned by `ledger`, requested by `lending`)

Two accounts are added to the plan §10 chart: `paypal_fees` (expense, program) and `member_overpayment` (liability, per member). Every posting carries `source_key = paypal_txn_id` (unique), so posting twice is impossible.

| Event | Debit | Credit |
| --- | --- | --- |
| Repayment, gross G, fee F, applied A ≤ outstanding, excess X = G − A | `paypal_program_cash` G − F; `paypal_fees` F | `loans_receivable:{loan}` A; `member_overpayment:{member}` X |
| Overpayment refund R | `member_overpayment:{member}` R | `paypal_program_cash` R |
| Refund or reversal of an applied repayment R | `loans_receivable:{loan}` R | `paypal_program_cash` R (plus the fee line, if PayPal returns the fee) |
| Auto-refund after closure | same as overpayment refund | |

The fee is the program's cost. The member is always credited the **gross** amount. This keeps "0%, no fees" true for the member, and makes the ledger match the gross, fee and net lines in Transaction Search.

## 6. API

All endpoints are OpenAPI-first in `contracts/openapi/seed-api.yaml`. Errors use RFC 9457 `application/problem+json`. Every write accepts an `Idempotency-Key` header; replaying with a different body hash returns `409 idempotency-key-reused`.

### 6.1 Member (`role=member`; ownership checked, so another member's loan returns 404)

| Method and path | Purpose | Notes |
| --- | --- | --- |
| `GET /api/v1/loans/{loanId}/schedule?version=current\|{n}` | Instalments with derived status, paid, remaining | ETag = `{loanId}:{version}:{allocationHash}` |
| `GET /api/v1/loans/{loanId}/schedule/versions` | All versions with reason and a diff vs the previous one | |
| `GET /api/v1/loans/{loanId}/timeline` | Bryntum Scheduler Pro payload | `resources`, `events`, `ghostEvents` (previous version), `timeRanges` (grace windows) |
| `GET /api/v1/loans/{loanId}/repayments` | Repayments and adjustments, cursor-paged | |
| `GET /api/v1/loans/{loanId}/payoff-quote` | Outstanding balance; valid for the business date | |
| `POST /api/v1/loans/{loanId}/payments/preview` | `{amountCents, onDate}` → allocation result and new schedule view, **no writes** | Pure engine call; powers "what if I pay $50 extra?" for the Coach |
| `POST /api/v1/loans/{loanId}/schedule/preview` | `{primitive: MOVE_DATE\|DEFER_TO_END\|REAMORTIZE, args}` → hypothetical version and diff, **no writes** | Pure engine call; F5 option previews, Bryntum AI what-ifs |
| `GET /api/v1/members/me/due-day-suggestion` | Suggested due day plus a plain reason | Used by F2 at application time |
| `POST /api/v1/loans/{loanId}/autopay` | Start enrolment → `{mandateId, approveUrl, status}` | Idempotent per loan |
| `POST /api/v1/loans/{loanId}/autopay/confirm` | Return-URL confirmation `{subscriptionId}` | Races safely with the webhook |
| `DELETE /api/v1/loans/{loanId}/autopay` | Switch to invoice mode | Cancels the mandate |
| `POST /api/v1/loans/{loanId}/payments` | `{amountCents, purpose}` → `{orderId}` | Whole dollars, 1 ≤ amount ≤ outstanding |
| `POST /api/v1/loans/{loanId}/payments/{orderId}/capture` | Capture after approval | Idempotent; returns the repayment |
| `POST /api/v1/loans/{loanId}/overpayment-refunds` | Refund the overpayment balance | |
| `GET /api/v1/loans/{loanId}/events` (SSE) | `ScheduleChanged`, `InstalmentStatusChanged`, `RepaymentReceived` | |

Example `GET /schedule` response:

```json
{
  "loanId": "4f1c…", "version": 2, "reason": "HARDSHIP_PAUSE", "businessDate": "2027-03-12",
  "principalCents": 120000, "outstandingCents": 90000, "overpaymentCents": 0,
  "collectionMode": "AUTOPAY", "dpd": 0, "bucket": "CURRENT",
  "instalments": [
    {"seq": 1, "dueDate": "2026-12-09", "amountCents": 15000, "paidCents": 15000, "status": "PAID", "paidOn": "2026-12-09"},
    {"seq": 3, "dueDate": "2027-02-09", "amountCents": 15000, "paidCents": 0, "status": "SCHEDULED", "movedFrom": {"version": 1, "dueDate": "2027-02-09"}}
  ]
}
```

### 6.2 Desk (`role=officer|checker|admin`)

| Method and path | Purpose |
| --- | --- |
| `POST /api/v1/desk/loans/query` | AG Grid server-side row model: `{startRow, endRow, sortModel, filterModel, rowGroupCols, groupKeys}` → `{rows, lastRow}` |
| `GET /api/v1/desk/portfolio/kpis?asOf=` | PAR30, collection rate, first-attempt success, cure rate, outstanding by bucket |
| `GET /api/v1/desk/portfolio/roll-rates?month=` | Bucket-to-bucket matrix with counts and balances |
| `GET /api/v1/desk/loans/{loanId}/integrity` | Allocation vs ledger vs PayPal for one loan (INV-2, INV-3) |
| `POST /api/v1/desk/loans/{loanId}/mandate/reconcile` | Force a reconcile now (admin) |
| `GET /api/v1/desk/cob-runs?from=&to=` | COB history, shard status, failures |

### 6.3 Internal jobs (`contracts/openapi/internal-jobs.yaml`; service token; idempotent)

| Method and path | Purpose |
| --- | --- |
| `POST /internal/jobs/cob/{date}/start` | Create the `cob_run` (or return the existing one) → `{shardCount, status}`. Refuses if `date − 1` is not COMPLETED |
| `POST /internal/jobs/cob/{date}/shards/{shard}` | Fire due timers for the shard from the saved cursor, in batches of up to `maxBatches` → `{done, processed, failed}` |
| `POST /internal/jobs/cob/{date}/finish` | Retry skipped loans, mark COMPLETED or DEGRADED, emit `CobCompleted` |
| `POST /internal/jobs/mandates/reconcile` | Reconcile every live mandate (daily safety net) |
| `POST /internal/jobs/repayments/reconcile/{date}` | FR-14 AC2: PayPal ↔ repayment rows |
| `POST /internal/demo-clock/advance` | `{days}`, enabled only with the `demo` profile; runs catch-up COBs |

### 6.4 Inbound integrations

| Method and path | Purpose |
| --- | --- |
| `POST /api/v1/webhooks/paypal` (ingress role) | Verify the signature, insert into the inbox, return 200 |
| `POST /api/v1/webhooks/zapier/sms` (ingress role) | Member SMS replies; HMAC + timestamp + replay cache; parsed by the tool-less extractor into `PAY`, `HELP`, `STOP`, `YES` |

### 6.5 Agent tools (`contracts/openapi/agent-tools.yaml`, read-only scope)
`get_repayment_schedule(loanId)`, `get_loan_aging(loanId)`, `list_repayments(loanId, limit)`. They return amounts, dates and statuses only. No payer ids, no PayPal ids.

## 7. Events (outbox → `contracts/events/domain-events.json`)

Every event has `eventId`, `occurredAt`, `businessDate`, `loanId`, `memberId`, `scheduleVersion`, `causationId`, `correlationId`. Ordering is per loan (partition key = `loanId`).

| Event | Extra payload | Consumers |
| --- | --- | --- |
| `ScheduleGenerated` / `ScheduleChanged` | `reason`, `instalments[]`, `diff[]` | engagement, desk-query, Bryntum SSE, payments (reconcile) |
| `AutopayActivated` / `AutopayCancelled` | `mandateId`, `cause` | engagement, desk-query |
| `InstalmentDueSoon` | `seq`, `dueDate`, `amountCents`, `paymentMethod` | engagement, hardship |
| `GraceNudgeDue` | `seq`, `dueDate`, `overdueCents`, `paymentLinkId` | engagement, hardship |
| `RepaymentReceived` | `repaymentId`, `source`, `grossCents`, `appliedCents`, `excessCents` | desk-query, credit-record, fraud, Bryntum SSE |
| `RepaymentAllocated` | `allocations[{seq, cents}]` | Bryntum SSE |
| `RepaymentFailed` | `seq`, `attemptNo`, `reasonCode` | engagement, hardship (Guardian), fraud |
| `RepaymentReversed` | `repaymentId`, `kind`, `amountCents` | fraud, desk-query, credit-record |
| `OverpaymentRecorded` / `OverpaymentRefunded` | `amountCents` | engagement, desk-query |
| `LateBucketChanged` | `from`, `to`, `dpd`, `cause` | hardship, desk-query, engagement, reserve (60+) |
| `InstalmentMissed` | `seq`, `dueDate` | credit-record, hardship |
| `LoanCured` | `fromBucket` | credit-record, desk-query |
| `LoanRepaid` | `closedOn`, `onPlanCount`, `missedCount` | credit-record, payments, engagement |
| `MandateDriftDetected` | `desired`, `observed` | desk-query, Slack via Zapier |
| `CobCompleted` | `status`, `timersFired`, `transitions`, `durationMs` | desk-query, Slack via Zapier, Elastic |

## 8. Concurrency and consistency

- **Per-loan serialisation:** every write path (repayment, adjustment, reschedule, COB timer) starts with `SELECT … FROM lending.loan WHERE id = ? FOR UPDATE`. COB uses `FOR UPDATE SKIP LOCKED` and re-queues skipped loans to the shard's tail, because the payment path that holds the lock re-ages the loan anyway.
- **One transaction per loan change:** repayment row, `ledger.post`, allocation projection, aging fields, bucket transition, timers and outbox all commit together (ADR 0002). If any step fails, nothing is visible.
- **Ledger lock order:** `ledger.post` locks account rows in ascending account-id order. This rules out deadlocks between concurrent postings that touch the same program cash account.
- **Program cash hot row:** postings append journal lines; the balance is computed (plan §16), so there is no hot-row update on `paypal_program_cash`.
- **API writes:** `If-Match: row_version` on mode changes. A mismatch returns `412`.
- **Inbox:** `webhook_inbox(paypal_event_id UNIQUE)` dedupes delivery. `repayment.paypal_txn_id UNIQUE` dedupes money. Both are needed, because PayPal can send an invoice payment as both `INVOICING.INVOICE.PAID` and `PAYMENT.CAPTURE.COMPLETED`.

## 9. Security

| Surface | Control |
| --- | --- |
| Member endpoints | PayPal login session; `loan.member_id = principal` checked in the use case, not only in the controller; 404 on mismatch |
| Desk | Staff passkeys; `officer`, `checker` or `admin` role per route; reads are logged |
| Internal jobs | Short-lived service token from a Render secret; network-restricted to the worker and workflow services |
| Webhooks | `verify-webhook-signature` (or local cert verification) before anything is stored as verified; unverified events are never acted on |
| Zapier inbound (SMS replies) | HMAC-SHA256 header with a shared secret; 5-minute timestamp tolerance; replay cache in Key Value |
| Payout and refund targets | Refunds go only to the original capture; never to a member-supplied wallet |
| Agents | Read-only scope; tools strip payer ids; untrusted SMS text goes through the tool-less extractor (`YES`, `PAY`, `HELP`, `STOP` only) |

## 10. Observability

- **Trace:** one trace per money event: ingress span → inbox → normaliser → `RecordRepayment` → `ledger.post` → outbox → consumers. Exported with OTel to Elastic APM. `loanId` and `paypalTxnId` are span attributes; payer ids are never attributes.
- **Metrics (Micrometer → Elastic):** `seed_repayment_ingest_latency_seconds`, `seed_autopay_first_attempt_success_ratio`, `seed_retry_success_ratio`, `seed_cob_duration_seconds`, `seed_cob_timers_fired_total`, `seed_par30_ratio`, `seed_mandate_drift_total`, `seed_parked_events`, `seed_invariant_violations_total`.
- **Alerts (Zapier → Slack):** COB not COMPLETED by 02:00 business time; any invariant violation; drift > 0; parked events older than 15 minutes; webhook lag over 5 minutes.
- **Nightly invariant job:** checks INV-1 to INV-8 across all active loans. Any violation is a Sev-2 alert and appears in the desk.

## 11. Failure modes

| Failure | Behaviour |
| --- | --- |
| Webhook duplicate | Inbox `UNIQUE(event_id)`, then `UNIQUE(paypal_txn_id)`: no-op |
| Webhook before the mandate exists (subscription activated after the first sale) | Parked; replayed when `AutopayActivated` commits; alert after 15 minutes |
| Webhook lost | Watchdog at due + 1 day, plus nightly Transaction Search reconcile → ingested with `source = RECONCILER` |
| PayPal 5xx or timeout on a command | Same `PayPal-Request-Id`, exponential backoff with full jitter (base 2 s, cap 5 min, 8 attempts), then `FAILED_FINAL` → desk dead-letter with a replay button |
| PayPal 4xx (validation) | `FAILED_FINAL` immediately; alert; the reconciler marks the desired state as unattainable → Payment Link fallback |
| DB down | Ingress cannot insert into the inbox → returns 5xx → PayPal retries (up to 3 days). No money state without a ledger entry |
| COB crash mid-shard | The cursor is saved per batch; the step is re-invoked by Render Workflows' retry; timers are deleted only in the same transaction that applies them |
| Two COBs for the same date | `cob_run` primary key; the second `start` returns the existing run |
| Reschedule (F5) during an in-flight autopay charge | The charge lands later and is folded against the new version; any excess goes to the last instalments or overpayment |
