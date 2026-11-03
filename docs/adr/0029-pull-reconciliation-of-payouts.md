# ADR 0029: Pull Reconciliation of Payouts

## Status
Proposed. Builds on [ADR 0013](0013-paypal-payouts-billing-catalog-transaction-search-and-venmo.md) (payout states, one event per outcome, `getPayoutItem` and `getPayoutBatch` kept for "a later sweeper"), [ADR 0017](0017-savings-and-loan-money-through-paypal.md) (the loan and the withdrawal wait for PayPal's report), [ADR 0024](0024-voiding-a-returned-disbursement-and-returning-unused-reserve-cover.md) (a payout returned after the loan was registered voids the loan) and [ADR 0028](0028-pull-reconciliation-of-savings-subscriptions.md) (the same idea for savings subscriptions).

## Context
Found in manual testing: an officer disbursed a loan, PayPal created the payout (status `SUCCESS` at PayPal), and Seed's payout stayed `PENDING` for ever, so the desk kept showing "PayPal has not confirmed yet". Seed learns how a payout ended only from webhooks (`PAYMENT.PAYOUTS-ITEM.*`, `PAYMENT.PAYOUTSBATCH.*`); a local backend has no public URL for PayPal to call, and a webhook lost in production does the same.

## Decision

### 1. Pull the report, apply it through the step the webhooks use
`PaymentsFacade.syncPayout(payoutId)` reads the payout from PayPal (`getPayoutItem` when the item id is known, else `getPayoutBatch`) and hands it to `PayoutFacts.apply` as a `Report` (`Report.of`, source `POLL`), exactly like the report in a webhook or in the create response. That one step already moves a payout forward at most once, records one `payout_transition` and publishes the one event (`PayoutSucceeded`, `PayoutFailed`, `PayoutReturned`) for the outcome, and the database guard refuses a backward move. So a webhook that arrives before, after or twice changes nothing, and a sync that finds nothing new publishes nothing. PENDING, PROCESSING and ONHOLD stay pending. An item word PayPal uses for a refusal, `DENIED` or `CANCELED`, maps to `FAILED` as the webhook does. A batch `DENIED` with no item status fails the payout with reason `BATCH_DENIED`, as before.

Unlike ADR 0028 the facts are published, not returned: lending and savings already finish a loan and a withdrawal from these events, and the ledger and schedule postings stay in the one place that owns them. A final payout (`FAILED`, `BLOCKED`, `RETURNED`) is never read. A delivered payout (`SUCCEEDED`) is read when asked for, because PayPal can take it back (`RETURNED`, ADR 0024), but the job leaves it alone. A payout PayPal has given no id yet is returned as stored.

### 2. Rate limit
At most one read per payout every `seed.payments.payout-sync-min-interval` (default `PT5S`), taken with one `UPDATE payments.payout SET last_synced_at = :now WHERE id = :id AND (last_synced_at IS NULL OR last_synced_at <= :now - gap)` (migration `payments/V6`): concurrent callers read once, and the time is recorded before the read, so a read that fails still counts. A call inside the gap returns the payout as stored. PayPal unreachable is `503 dependency-unavailable`; PayPal refusing the read is `409 paypal-rejected`; the payout is unchanged in both. `syncPayout` holds no transaction: the claim commits first, PayPal is read, and `PayoutSyncStep` applies the report in its own short transaction with the payout row locked and read again.

### 3. Job
`PaymentsFacade.syncOpenPayouts(limit)` reads, least recently read first, payouts that are `REQUESTED`, `PENDING` or `UNCLAIMED`, have a PayPal item or batch id, and were created at least `seed.payments.payout-reconcile-min-age` (default `PT20S`) ago, so the create call's own answer comes first. A payout that fails is logged and skipped. `POST /internal/jobs/payouts/reconcile?limit=` (service token; default 50, 1 to 500) returns `examined`, `synced`, `failed`. `PayoutReconciler` runs it every `seed.jobs.payout-reconcile-ms` (default one minute, first run after 30 seconds, `seed.payments.payout-reconcile-batch` payouts per run; off where `seed.jobs.enabled` is false, as in tests).

### 4. Desk and member surfaces
`POST /api/v1/desk/applications/{applicationId}/payout/sync` (officer, checker or admin) reads the application's current payout and returns the application (`LoanApplication`, the schema `disburse` returns). `404 application-not-found` for an unknown application, `404 payout-not-found` for one never paid out or whose payout payments does not know; `403` for a member; `401` without a session. It is a safe re-read, so there is no `Idempotency-Key`.

The response must show the refreshed application, and the events are applied after commit by another thread, so lending applies the same answer itself: `SyncDisbursementPayoutUseCase` reads through the port `DisbursementPayouts.sync` (implemented by `PaymentsPayoutAdapter`) and calls `onPayoutSucceeded`, `onPayoutFailed` or `onPayoutReturned`, the very methods `PayoutEventsListener` calls. They are idempotent on the application's current payout reference, so the event of the same read, a late webhook and a second press change nothing. No new module dependency: lending already depends on payments.

`POST /api/v1/savings/goals/{goalId}/sync` (ADR 0028) also reads the goal's withdrawals that are still `REQUESTED` and applies each answer through `WithdrawalOutcomeUseCase`, the steps its event listener uses, whatever the state of the goal's subscription. A withdrawal whose payout cannot be read now is logged and left for the next refresh or the job; it never fails the refresh of the goal.

### 5. Web (desk)
The payout panel, while the application is `DISBURSING`, calls the Server Action `syncApplicationPayout` every 5 seconds (not while the tab is hidden, at most 24 times: about two minutes), which calls the route and refreshes the page; then it shows "Check again", the same action by hand. The browser never calls the backend.

## Alternatives considered
- **Return facts and apply them in the caller, as ADR 0028 does.** Rejected for payouts: loan registration and withdrawal booking already hang off the three events, and applying them from the events keeps one writer of the books. Lending and savings additionally apply the answer in the request only so the response can show it.
- **Treat `PAYOUTSBATCH.SUCCESS` as success.** Rejected in ADR 0013 and kept: a batch read reports the item's own status.
- **Read delivered payouts in the job.** Rejected: every payout would be read for ever for the rare return; PayPal's return webhook, or a desk check, covers it.
- **Rate limit in memory.** Rejected: several api instances; the row is the one shared fact.

## Consequences
- Migration `payments/V6__payout_sync.sql`: `payout.last_synced_at`, a partial index for the job, and `payout_transition.source` may now be `POLL`.
- Settings: `seed.payments.payout-sync-min-interval` (`SEED_PAYMENTS_PAYOUT_SYNC_MIN_INTERVAL`, `PT5S`), `seed.payments.payout-reconcile-min-age` (`SEED_PAYMENTS_PAYOUT_RECONCILE_MIN_AGE`, `PT20S`), `seed.payments.payout-reconcile-batch` (`SEED_PAYMENTS_PAYOUT_RECONCILE_BATCH`, 50), `seed.jobs.payout-reconcile-ms` (`SEED_JOBS_PAYOUT_RECONCILE_MS`, 60000).
- A payout whose create response carried no item id and whose batch has no items yet reads as pending from the batch header; the item id is stored from the first read that has it.
- Real sandbox behaviour unverified: that `GET /v1/payments/payouts-item/{id}` always carries `payout_batch_id` and `transaction_status` (the batch read is the fallback when the item id is unknown), and the exact wording of an item's refusal `errors.name` for the plain-language desk text.
- Every open payout costs one PayPal read per minute until it ends or PayPal gives up on it (an unclaimed payout is returned after 30 days).

## Tests
`PayoutSyncServiceTest` (item or batch read, gap, final and delivered payouts, no ids, unknown payout, outage and refusal, batch counts, report mapping), `PayoutStateMachineTest` (item words, never backwards), `SyncDisbursementPayoutUseCaseTest`, `SyncGoalWithdrawalsTest`, `PayoutSyncIT` (end to end over HTTP: paid without a webhook registers the loan, schedule and ledger once; webhook before, after and twice; failed, denied and returned before and after registration; batch read; rate limit, outage, refusal; 404, 403, 401; the job with one failing payout; a goal refresh ending a withdrawal), `ContractCoverageIT`.
