# ADR 0027: Payout Recipient Fingerprint, Retried Savings Webhooks, Ordered Subscription Reports and Three Settings That Fail Loudly

## Status
Proposed. Builds on [ADR 0013](0013-paypal-payouts-billing-catalog-transaction-search-and-venmo.md) (payouts and savings subscriptions), [ADR 0015](0015-desk-read-models-keyset-grids-and-replayable-projections.md) (desk cursors, the saving streak), [ADR 0017](0017-savings-and-loan-money-through-paypal.md) (savings and loan money), [ADR 0019](0019-reserve-safety-fund-and-fraud-signals.md) and [ADR 0021](0021-wiring-desk-reserve-fraud-record-and-withdrawal-messages.md) (fraud facts and the wiring module), [ADR 0020](0020-member-reads-verified-actors-and-reserve-cover-on-the-schedule.md) (ordering of subscription reports).

## Context
Six loose ends from the payments, savings, fraud, desk-query and member Home slices.

1. **Fraud could not see who a payout went to.** The `PAYOUT_RECIPIENT_CHANGE` rule compared the wallet type only (PayPal or Venmo), because no event carried the recipient. A member whose email changed at a later PayPal login, or whose payout went to another account behind the same wallet type, was invisible.
2. **A savings webhook that beat the goal's commit was dropped.** `CreditPaymentUseCase` logged and returned for a goal id it could not find. That is right for an id that does not exist and wrong for a goal whose transaction has not committed yet: the deposit was lost for good.
3. **Out-of-order PayPal reports.** A `SUSPENDED` made in PayPal after a resume in Seed is ordered by the resource's `update_time`. Whether PayPal sends `update_time` on every event is unverified. The payments table that mirrors the subscription status also let an older report overwrite a newer one.
4. **Pause and resume used `Instant.now()` inline.** On purpose, but nothing said so and no test could set the time.
5. **Desk-query had three loose settings.** The cursor key could be empty in production, which gives every api instance its own random key and breaks a page opened on one instance and continued on another. The application SLA and the due-soon window were configurable but pinned by no test. The saving streak counted UTC calendar months.
6. **A member without a PayPal login gets `409 payout-recipient-missing`** on a withdrawal or a loan payout, and the web could not warn first.

## Decision

### 1. A keyed fingerprint of the payout recipient, announced with every payout request
`payments` publishes `PayoutRequested(eventId, memberId, referenceId, purpose, wallet, recipientFingerprint, amountCents, occurredAt)` in the transaction that records a payout, once per payout reference (a replay, or an attempt rolled back by a transient PayPal failure, publishes nothing extra; a payout PayPal refuses still counts, because the attempt is what matters). `SendPayout` gained optional `memberId` and `purpose` (`WITHDRAWAL` or `DISBURSEMENT`); they go together, and the six-argument form announces nothing.

The fingerprint is HMAC-SHA256 under a server secret of the normalised recipient: `<type>:<value>`, where an email is trimmed and lower-cased, a phone number keeps its digits and a leading plus, a payer id is upper-cased. The type is part of the identity. The secret is `seed.payments.recipient-fingerprint-key` (`SEED_PAYMENTS_RECIPIENT_FINGERPRINT_KEY`), 32 or more characters, identical on every instance. A plain hash of an email could be recomputed from a guess; a keyed one cannot. The raw email, phone or payer id never leaves `payments` because of this event, and the event is not logged with them.

`fraud` listens (it already depends on `payments`, so no wiring adapter is needed) and calls the new `FraudFacade.payoutRecipient(memberId, fingerprint, at, sourceKey)`. Like `payoutDestination` (ADR 0021), it keeps the member's latest recipient as a hash (`fraud.payout_recipient`, migration `V4`, the fingerprint hashed once more with fraud's own salt): the first recipient is a baseline and records nothing, the same one again records nothing, a different one records a `PAYOUT_RECIPIENT_CHANGED` fact. The existing rule is unchanged (a change in the 72 hours before a disbursement, weight 40) and its evidence carries counts and hours only. The wallet-type signal from `FraudFeed` stays; both feed the same fact kind.

**Key handling.** Outside the dev, test and demo profiles an empty key stops the start, with a message that names the setting. Those profiles use a random key per process (dev has a fixed one in `application-dev.yml`). Changing the key makes every member's next payout look like a change once; that costs an officer a glance at most, because a change is only a signal close to a disbursement. A key shorter than 32 characters is refused in every profile.

**Who passes the member.** Savings passes it for withdrawals (this ADR). Lending's disbursement adapter builds its own `SendPayout`; it must pass `memberId` and `PayoutPurpose.DISBURSEMENT` (see "Needed from lending"). Until it does, disbursements announce nothing and only the wallet-type signal covers them.

### 2. A savings report that beats its goal's commit is retried; one for a goal that never exists is dropped
The listener calls `CreditPaymentUseCase.creditEvent(goalId, txnId, amount, source, happenedAt)`. When the goal row is not visible and the event is younger than `seed.savings.goal-visibility-window` (default `PT1H`), it throws `GoalNotVisibleYetException`. Nothing is claimed or written, the event publication stays incomplete, and the registry delivers it again (`BackgroundJobs` resubmits publications older than 60 seconds on each `event-retry-ms` pass; `settle()` does the same in tests). Once the event is older than the window, a missing goal is taken as one that will never exist: it is logged once as a warning and the event completes.

The window is the **poison limit** and it is stateless: the event's own time against real time, so no counter table is needed and a restart does not reset it. About sixty tries at the default rate. The same rule applies to a subscription state report (`SubscriptionStateUseCase`), which would otherwise leave a goal waiting for approval until it expired. `credit(...)` keeps its contract (drops an unknown goal at once) and its tests; a window of zero never waits. The time is real time on purpose, like every time PayPal dates (decision 4).

Open choice, not taken: counting delivery attempts in a table. It is exact but needs a migration, a cleanup and a second source of truth for something the event already says.

### 3. Subscription reports are ordered, with fallbacks, and the stored status cannot go back
A report is dated by, in order: the resource's `update_time`; the webhook's `create_time`; the moment the webhook reached Seed (`webhook_inbox.received_at`, carried on `InboxEvent`), so that two reports with no time at all keep the order in which they were received even if one was parked and processed late; and only for a report that was never stored, the real clock. An unparseable time counts as missing. `SubscriptionStateUseCase` already refuses a report older than the state the goal knows.

`payments.savings_subscription` gained `status_at` (migration `V4`). A reported status is stored only if it is not older than `status_at`, and `CANCELLED` and `EXPIRED` are never replaced by another status. Seed's own change (suspend, resume, cancel) is applied as it stands and dated now. Before this, a late `SUSPENDED` could overwrite a newer `ACTIVE` there, and the "has PayPal ended it" check of a resume read a wrong answer.

### 4. Pause and resume keep the real clock, and say so
`ManageGoalUseCase` stamps a pause or a resume with a `java.time.Clock` (real time), not `BusinessClock`. PayPal dates its reports in real time and never sees the demo clock, which can run months ahead; stamping with the business clock would make every PayPal report look older than the member's last action. The clock is injected (`ObjectProvider<Clock>`, falling back to `Clock.systemUTC()`), the class comment holds the reason, and `PauseResumeStampTest` sets the real clock against a business clock four months ahead. The same real time source dates `GoalVisibility` and the receipt fallback in the webhook normaliser.

### 5. Desk-query
- **Cursor key fails fast.** `DeskQuerySettings` refuses to start with an empty `SEED_DESK_QUERY_CURSOR_KEY` unless the active profiles contain `dev`, `test` or `demo` (those keep generating a key per process; `dev` has a fixed one). A deployment that sets no profile is production. The message names the setting and why.
- **SLA settings pinned.** The application SLA is `submittedAt + seed.desk-query.application-sla` (default `PT24H`) and "due soon" is `seed.desk-query.sla-due-soon` (default `PT4H`). Both were already settings; `DeskQuerySettingsTest` pins the defaults and the override, `DeskQueryIT` pins the shipped configuration, and `ProjectorsTest` already pins `slaDueAt`.
- **The streak counts calendar months in the business time zone.** The streak lives in `desk-query` (`SavingStreak.monthOf`), not in `savings` (`SavingsStreak` counts deposits, takes its months as an argument and has no caller that supplies calendar months). It now uses `BusinessClock.PROGRAM_ZONE` (`America/Los_Angeles`) through `BusinessClock.businessDateOf`, like every other business date, so a deposit on the evening of the 31st counts for that month. This changed `SavingStreakTest.depositMonthsAreUtcCalendarMonths`, which pinned the old rule; it is replaced by tests of the new boundary, including daylight saving time. Deposit months already stored keep the month they were stored with until the projection is rebuilt (ADR 0015); a rebuild applies the new rule to all of them.

### 6. The Home page says whether a payout can be sent
`GET /api/v1/members/me/home` gains `payoutRecipientReady`: true when `IdentityFacade.payoutRecipient` returns a recipient, false when it does not. It is a top-level field and not part of `profile`, because a member who never signed in with PayPal has no profile either (`profile: null`), and that is exactly the member to warn. It is omitted only when identity could not answer, so the web shows no warning rather than a wrong one. It carries no personal data. The contract is updated; the web is told by `pnpm gen:api`.

## Alternatives considered
- **Compare the email, hashed with a plain SHA-256.** Rejected: a stolen table plus a list of likely emails recomputes it. The keyed hash needs the server secret too.
- **Put the raw recipient in the event and hash it in fraud.** Rejected: the recipient would sit in `event_publication` (a durable table) and in every consumer's reach. The fingerprint is computed before the event exists.
- **Reuse the `payout_destination` slot for the fingerprint.** Rejected: the wallet-type reference and the fingerprint would always differ and flag every member. They are two slots feeding one fact kind.
- **A retry counter for the missing goal.** See decision 2.
- **Treat a missing goal as always retryable.** Rejected: an id that never exists would be delivered every minute for ever.

## Consequences
- Two new required settings outside dev, test and demo: `SEED_PAYMENTS_RECIPIENT_FINGERPRINT_KEY` and `SEED_DESK_QUERY_CURSOR_KEY`. Every role loads both modules, so every role needs both values; `infra/render.yaml` and `.env.example` must list them (owner).
- New setting `seed.savings.goal-visibility-window` (`SEED_SAVINGS_GOAL_VISIBILITY_WINDOW`, default `PT1H`).
- Migrations: `payments/V4__savings_subscription_status_time.sql`, `fraud/V4__payout_recipient.sql`.
- A report for a goal that is really missing is now retried for up to an hour before it is dropped, and logs the drop once. A listener failure shows in the event publication registry as an incomplete publication for that time.
- Rotating the fingerprint key causes one harmless "change" per member.
- Loan disbursement is not covered by the recipient fingerprint until lending passes `memberId` and the purpose.

## Needed from other areas
- **lending:** `PaymentsPayoutAdapter.send` builds `new SendPayout(referenceId, amountCents, ..., note)`; add the member id and `PayoutPurpose.DISBURSEMENT` (the `DisbursementPayouts.send` port has no member id, so it needs one parameter more). The eight-argument constructor of `SendPayout` is already in place.
- **owner:** `infra/render.yaml` and `.env.example` for the two required keys; `pnpm gen:api` for `payoutRecipientReady`.

## Tests
`RecipientFingerprintsTest`, `WebhookNormalizerOrderingTest`, `GoalVisibilityTest`, `PauseResumeStampTest`, `DeskQuerySettingsTest`, `SavingStreakTest`, `RecipientRepositoryTest`, `MemberHomeServiceTest`; `FraudIT` (first payout no flag, same recipient no flag, changed recipient flags and the evidence does not name it, one announcement per reference), `SavingsWebhookRetryIT`, `SavingsWebhookOrderingIT`, `HomePayoutRecipientIT`, `DeskQueryIT` (shipped SLA settings).


## Closing pass

What the web still lacked, and one backend debt, resolved without a new decision record.

### Member Home extras (`GET /api/v1/members/me/home`)
- **`recentActivity`** (new section, name `recentActivity` in `sectionErrors`): the member's last `seed.home.activity-limit` items (default 10, held between 1 and 50), newest first. Each is `kind`, `amount` (`cents`, `currency`), `occurredAt`, `goalId`, `loanId`. Kinds: `DEPOSIT`, `WITHDRAWAL`, `WITHDRAWAL_RETURNED`, `LOAN_DISBURSED`, `LOAN_PAYMENT`, `LOAN_PAYMENT_REVERSED`, `COVER_APPLIED`, `LOAN_VOIDED`. No hardship case, plan or score appears: nothing of hardship is projected. An empty list is "nothing yet", not an error; a failure is `unavailable` for this section alone.
- **Where it is read from.** No facade held a member's loan payments, and lending was not ours to change, so the list is a read model in `desk-query` (`deskquery.member_activity`, migration `V4`, `ActivityProjector`, `ActivityListeners`), fed by the same kind of idempotent, replayable handlers as the desk grids (ADR 0015). It is keyed by the fact (a deposit by its PayPal transaction, a loan payment by its transaction id, a loan payout by loan), never by the event, so a repeat, a replay and the rebuild below land on one row. It holds the member id and no other personal data. The app reads it through `DeskQueryFacade.memberActivity`; `Projection.ACTIVITY` resets it. Lending events carry a business date and no instant, so such a row is dated at the start of that business day in the program zone: the same input always gives the same time, which a replay needs. The amount of `COVER_APPLIED` is what the loan took (`appliedCents`), not what the fund moved.
- **Months to go.** `creditRecord.nextStep.onTimeSavingMonthsToGo` already is the months of on-time saving the next level still needs, computed by `RecordLevels.nextStep` from the record; it is not a second number to keep. What the page lacked for a progress bar is the total, so `nextStep.requiredOnTimeSavingMonths` is added, read from the rules' own ladder (`RecordLevels.ladder()`); months done so far is required minus to go.
- **Next deposit date.** Each goal card has `nextDepositDate`. PayPal's `billing_info.next_billing_time` is stored in `payments.savings_subscription.next_billing_time` (migration `V5`): from the subscription webhooks (only when the report is not older than the stored status, with the rule of decision 3), cleared when the subscription is not active, and refreshed by one read of the subscription after each deposit sale (a sale report does not say where the next charge falls; if PayPal cannot answer, the stored time stays). Savings turns it into a business date for an `ACTIVE` goal only (`MemberGoalView.nextDepositDate`); Home leaves out a date that is already past, because that is a report not yet renewed and not a promise.
- **Deposit history.** `GET /api/v1/savings/goals/{goalId}/deposits?limit=` (1 to 200, default 50): the member's own goal (another member's, an unknown goal and a staff session are `404`; no session is `401`), newest first, each `source` (`SUBSCRIPTION` or `TOPUP`), `amount` (decimal, like the rest of the savings routes), `amountCents`, `currency`, `creditedAt`. It reads `savings.credited_payment`, so it holds exactly the money PayPal confirmed; it carries no PayPal reference. A deposit made through the development-only legacy top-up route is not in it.

### Fair check by application
`GET /api/v1/desk/underwriting/assessments/latest?applicationId=` (officer, checker or admin) returns the newest assessment linked to the application as a `FairCheckCard`, or `404 assessment-not-found`. It is a route of its own and not a filter on the list, because a filter that changes the response from an array to one object cannot be typed in the contract or the generated client. The list stays as it was.

### Amount change on a goal
It exists: `POST /api/v1/savings/goals/{goalId}/amount` (`changeSavingsAmount`, body `monthlyAmount`, whole dollars). It is in the contract, and `SavingsSubscriptionIT` pins the happy path and the `422` for cents; `HomeActivityIT` adds another member's goal (`404`), no session (`401`) and a goal still waiting for approval (`409`). The route is `POST`, not `PATCH`; the contract is the reference.

### Saving months in the business zone, rebuilt
Months stored before the rule of decision 5 were cut in UTC, and the event that carried a deposit cannot be asked again for its month once the publication log is trimmed. `SavingMonthsRebuild` recomputes them from the deposits savings holds (`SavingsFacade.membersWithDeposits`, `depositsOfMember`: each deposit with the instant it was credited), applying `SavingStreak.monthOf` to each. Per member, in one transaction, under the lock the deposit listener takes, so a deposit that lands during the run is either read by it or added after it by its own event, in the same month: nothing lost, nothing counted twice. It replaces that member's months, sets the streak (also in the application queue) and records each deposit in the activity list, which started after some deposits were made. A member with no credited deposit is left as stored. `DeskQueryFacade.rebuildSavingMonths()` runs it at any time. `SavingMonthsRebuildRunner` runs it once at the first start after this change, on whichever role starts first: `deskquery.rebuild_done` (migration `V4`) holds the marker, written only after every member was rebuilt, so a failed run is tried again at the next start and a failure never stops the start. This reads the money and not the log, so it does not depend on log retention (the replay of ADR 0015 still refills the other projections).

### Consequences
- Migrations: `payments/V5__savings_subscription_next_billing.sql`, `desk-query/V4__member_activity_and_rebuilds.sql`. New setting `seed.home.activity-limit` (`SEED_HOME_ACTIVITY_LIMIT`, default 10).
- The activity list begins when this ships, except deposits (backfilled by the rebuild); earlier withdrawals, loan payouts and payments are not in it. Replaying the publication log does not add them, because the new listeners are new subscribers.
- The rebuild holds one member's months in memory at a time and pages members 200 at a time.
- A member whose only deposits came through the legacy top-up route (development, test and demo) loses those months if they also have a credited deposit.

### Tests
`ActivityProjectorTest`, `SavingMonthsRebuildTest` (a deposit at 06:30 UTC on 1 November lands in October; midnight in Los Angeles; idempotence; a member with no deposits; paging; the marker), `NextDepositAndDepositListTest`, `WebhookNormalizerNextBillingTest`, `HomeActivityTest`; `HomeActivityIT`, `DeskRebuildAndFairCheckIT`.
