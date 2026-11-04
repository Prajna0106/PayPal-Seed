# ADR 0028: Pull Reconciliation of Savings Subscriptions

## Status
Proposed. Builds on [ADR 0013](0013-paypal-payouts-billing-catalog-transaction-search-and-venmo.md) (savings subscriptions), [ADR 0017](0017-savings-and-loan-money-through-paypal.md) (a goal waits for PayPal to report the approval), [ADR 0020](0020-member-reads-verified-actors-and-reserve-cover-on-the-schedule.md) and [ADR 0027](0027-payout-recipient-fingerprint-retried-savings-webhooks-and-ordered-reports.md) (ordering of subscription reports, retried reports).

## Context
Found in manual testing: a member creates a goal, approves the PayPal subscription in the sandbox and returns to the app, and the goal stays `PENDING_APPROVAL` with $0 for ever. Seed learns about the approval (`BILLING.SUBSCRIPTION.ACTIVATED`) and about each deposit (`PAYMENT.SALE.COMPLETED`) only from webhooks, and the sandbox cannot call a machine with no public URL. A webhook that is lost in production does the same.

## Decision

### 1. Pull the facts, apply them through the steps the webhooks use
`PaymentsFacade.syncSavingsSubscription(goalId)` reads the goal's newest subscription (`getSubscription`) and its sales of the last 35 days (`subscriptionTransactions`) and returns them as the facts `WebhookNormalizer` publishes, in the same records: one `SavingsSubscriptionStateChanged` (ACTIVE, SUSPENDED, CANCELLED, EXPIRED; PayPal's other states report none) and one `SavingsDepositReceived` per COMPLETED sale, gross amount and fee as the sale webhook carries them, the sale id as the transaction id. It publishes nothing. It stores the status and the next charge the way a webhook does (`SavingsSubscriptionRepository.status` keeps its ordering rule; a subscription that is not active has no next charge).

The caller applies the facts. Savings does it through `SubscriptionStateUseCase.apply` and `CreditPaymentUseCase.credit`, which are exactly what its event listener calls. So there is one path for a goal's state and one for crediting, and both are idempotent already: the state by the time PayPal gave it (a report older than the goal's latest state is ignored), the deposit by the PayPal transaction id (`savings.credited_payment`). A webhook that arrives before, after or twice changes nothing; the integration tests pin each order.

Why not publish events from the pull: the web needs the refreshed goal in the same response, and an event is applied after commit by another thread. Applying in the request, and publishing nothing, also leaves no event rows behind for a read that finds nothing new, and a sale that the goal cannot take yet (it is paused or closed meanwhile) simply stays in PayPal's window and is credited by a later read.

### 2. The time of a state report
`status_update_time` of the subscription, else `update_time` (`Subscription.statusChangedAt`, new and optional). Without either, the time stored for the subscription's status (`payments.savings_subscription.status_at`), which can neither overtake a newer report nor lose to an older one; with nothing stored, the real clock. Never "now" when something is stored: a read made before the member paused in Seed must not undo the pause.

### 3. Member refresh
`POST /api/v1/savings/goals/{goalId}/sync`: signed-in member, own goal (another member's, an unknown goal and a staff session are `404`; no session is `401`). It is a safe re-read, so no `Idempotency-Key`. It returns the goal in the shape of `GET /api/v1/savings/goals/{goalId}` (`SavingsGoal`). At most one read per goal every `seed.savings.sync-min-interval` (default `PT5S`): a call inside that time, and a goal with no live subscription (completed, cancelled, expired, or a Venmo goal), returns the stored goal untouched. The right to read is taken with one `UPDATE savings.savings_goal SET last_synced_at = :now WHERE id = :id AND (last_synced_at IS NULL OR last_synced_at <= :now - gap)` (migration `savings/V5`), so concurrent calls read once; it is recorded before the read, so a read that fails still counts. PayPal unreachable is `503 dependency-unavailable`; PayPal refusing the read is `409 paypal-rejected`; the goal is unchanged in both.

### 4. Reconcile job
`ReconcileGoalsUseCase` reads, in one bounded batch (`seed.savings.reconcile-batch`, default 50, least recently read first): goals `PENDING_APPROVAL` whose approval window is still open (a goal waiting for a webhook is the case that matters, so it is read on every run, held back only by the five second gap), and goals `ACTIVE` or `PAUSED` not read for `seed.savings.reconcile-gap` (default `PT1H`). A goal that fails is logged and skipped; the next run tries it again. `GoalReconciler` runs it every `seed.jobs.savings-reconcile-ms` (default 15 minutes, first run 30 seconds after start; off where `seed.jobs.enabled` is false, as in tests). `POST /internal/jobs/savings/reconcile?limit=` (service token) runs the same batch and returns `examined`, `synced` and `failed`.

### 5. Layout
Payments: `SavingsSyncService` (package-private, used by `PaymentsService`). Savings: ports `SavingsPayments.readSubscription` (default empty, so existing doubles compile) and `GoalSyncLog`; `PaymentsAdapter` and `GoalSyncRepository` implement them; `SyncGoalUseCase` and `ReconcileGoalsUseCase` hold no transaction of their own (each step is its own, so a deposit the goal cannot take rolls back alone); `SavingsSyncController` and `SavingsJobsController`. `last_synced_at` stays out of `SavingsGoal` (the upsert in `SavingsRepository.save` never touches it).

## Alternatives considered
- **Publish the facts as events and let the listeners apply them.** Rejected: asynchronous, so the response could not carry the refreshed goal; and a read that finds nothing new would still leave publications.
- **A sale table in payments to publish each sale once.** Rejected: the credit claim already makes a repeat free, and the pull publishes nothing.
- **Ask PayPal for the window from the subscription's start.** The start time is not stored; 35 days covers a missed monthly sale with margin, and a longer gap is a job that did not run for a month.
- **Rate limit in memory.** Rejected: several api instances; the database row is the one shared fact.

## Consequences
- Migration: `savings/V5__goal_sync.sql` (`last_synced_at`, a partial index for the job). No payments migration.
- Settings: `seed.savings.sync-min-interval` (`SEED_SAVINGS_SYNC_MIN_INTERVAL`, `PT5S`), `seed.savings.reconcile-gap` (`SEED_SAVINGS_RECONCILE_GAP`, `PT1H`), `seed.savings.reconcile-batch` (`SEED_SAVINGS_RECONCILE_BATCH`, 50), `seed.jobs.savings-reconcile-ms` (`SEED_JOBS_SAVINGS_RECONCILE_MS`, 900000).
- On a machine with no public webhook URL the web calls the sync route when the member returns from PayPal; the job covers a member who never returns.
- Real sandbox behaviour unverified: whether `status_update_time` is always present (the fallbacks above cover its absence), and whether the subscription transactions list needs a window under 31 days (35 days is read in one call; if PayPal refuses it the read is `409 paypal-rejected` and the window must be split).
- A goal read every 15 minutes while pending costs one subscription read and one transactions read of PayPal per goal and run; goals expire after 24 hours.

## Tests
`SavingsSyncServiceTest` (state and deposit facts, ordering fallbacks, outage and refusal), `SyncGoalUseCaseTest` (activate and credit once, repeat, webhook first, stale state, cancelled, rate limit, failing batch), `SavingsSyncIT` (end to end over HTTP: approval and sale credited once, webhook before and after, twice-delivered webhook, cancelled, pause and resume ordering, another member's goal, rate limit, PayPal outage, the job with one failing goal, window and recently-read exclusions, authorisation), `ContractCoverageIT`.
