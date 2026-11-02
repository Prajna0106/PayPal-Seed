# ADR 0017: Savings and Loan Money Through PayPal

## Status
Proposed. Builds on [ADR 0013](0013-paypal-payouts-billing-catalog-transaction-search-and-venmo.md) (the payments facade it calls), [ADR 0005](0005-paypal-mandate-desired-state-reconciliation.md) and [ADR 0006](0006-repayment-allocation-waterfall.md). Vendor details marked "unverified" are listed at the end.

## Context
ADR 0013 gave `payments` the calls it needed (savings subscription, one-off top-up, payout) and left the wiring to the callers. Until now:

1. `POST /goals` expected the client to bring a PayPal subscription id, and nothing checked that the member had agreed to anything.
2. A savings withdrawal recorded a payout id the client made up, and a loan disbursement registered the loan without paying the member.
3. `payments` imported `savings` (the webhook controller called `SavingsFacade`), so `savings` could not call `payments`: Spring Modulith rejects the cycle.
4. `CreateGoalUseCase` was not transactional, so `GoalCreatedEvent` was published outside any transaction and never reached an `@ApplicationModuleListener`.
5. `PaymentsService` hashed some requests from `Map.of(...)` with several entries, whose iteration order changes from one JVM run to the next.

## Decision

### 1. `payments` no longer imports `savings`; it announces what PayPal said
The webhook controller only verifies and stores. The inbox processor turns the stored event into facts, now including three for savings and one for the reserve fund (`payments.api.events`):

| Event | When | Consumer |
| --- | --- | --- |
| `SavingsDepositReceived(goalId, memberId, subscriptionId, paypalTxnId, amountCents, ...)` | `PAYMENT.SALE.COMPLETED` on a savings subscription Seed created | savings credits the goal |
| `SavingsSubscriptionStateChanged(..., state)` | `BILLING.SUBSCRIPTION.ACTIVATED`, `RE-ACTIVATED` (`ACTIVE`), `SUSPENDED`, `CANCELLED`, `EXPIRED` | savings activates or pauses the goal |
| `DonationCaptured(paypalTxnId, customId, grossCents, feeCents, ...)` | `PAYMENT.CAPTURE.COMPLETED` whose `custom_id` starts `reserve-donation:` | reserve (listener added by its owner) |

The goal is named by the recorded `payments.savings_subscription` row, never guessed from the payload. A subscription webhook for a `goal:` subscription whose row is not committed yet is parked and woken by the insert, like a sale before its subscription. A sale on any other subscription stays on the loan path. Two consequences to know: a goal created with a client-made legacy subscription id has no row, so its webhooks do not credit it (use the legacy top-up in dev); and the old direct call ran at receipt, the new one runs from the inbox, which is retried and idempotent.

`PaymentsFacade` gains `savingsSubscriptionOf`, `suspendSavingsSubscription`, `activateSavingsSubscription`, `reviseSavingsSubscription` and `cancelSavingsSubscription`. They reuse the command kinds of ADR 0013 (`SUSPEND_`, `ACTIVATE_`, `REVISE_`, `CANCEL_SUBSCRIPTION`), take a caller `requestId` (a goal's change counter) so every toggle is its own PayPal call and a retry is the same call, and treat an ended subscription as nothing to do (suspend, cancel, revise) or `409 savings-subscription-ended` (activate).

### 2. A goal waits for PayPal to say the member agreed
`StartGoalUseCase` makes the goal and calls `createSavingsSubscription` in one transaction. The goal id is derived from `(member, Idempotency-Key)`, so a retry after a timeout is the same goal and the same PayPal request; a different body under the key is `409`. The response is `PENDING_APPROVAL` with `approvalUrl` and `approvalExpiresAt` (`seed.savings.approval-window`). While pending, `acceptsDeposits()` is false: `recordDeposit`, top-ups, pausing and withdrawing are refused. `SavingsSubscriptionStateChanged(ACTIVE)` makes the goal `ACTIVE`; a sweeper (`GoalApprovalSweeper`, `seed.jobs.enabled`) expires the ones never approved (`EXPIRED`). An approval that arrives after expiry cancels the subscription at PayPal so no money flows to a closed goal. Goals waiting for approval count toward the limit of three.

A deposit is credited once per PayPal transaction id: `savings.credited_payment` claims the id in the same transaction as the credit, so a refusal (goal not yet approved) rolls the claim back and the event is delivered again, and a duplicate does nothing. Reports about an earlier subscription of the goal are ignored (only the goal's current subscription counts). Cancelling or suspending in PayPal pauses an active goal; `resume` activates a suspended subscription, or, when PayPal ended it (it cannot come back), starts a new one and returns the goal to `PENDING_APPROVAL`. Changing the amount revises the quantity; PayPal may want the member to agree, and `approvalUrl` carries that.

**Venmo.** Subscriptions cannot be funded with Venmo. A Venmo goal has no subscription and is active at once; `POST /goals/{id}/topup` creates an order (`createSavingsTopUp`, funding = the goal's wallet) and returns its approval URL, `POST /goals/{id}/topup/{orderId}/capture` captures it, and the goal is credited by `SavingsTopUpCaptured` (claimed by capture id, so the capture call and the webhook credit once between them).

**Legacy.** `payPalSubscriptionId`, `payPalPayoutId` and `payPalCaptureId` in the request bodies still work in the `dev`, `test` and `demo` profiles (existing tests drive savings with them) and are `422 legacy-id-not-allowed` everywhere else. Their Javadoc says so.

### 3. A withdrawal is a saga around a payout
`WithdrawUseCase` locks the goal row, moves the amount from `currentBalance` to the goal's **reserve** (`reserved_cents`), inserts `savings.withdrawal` (id derived from `(goal, Idempotency-Key)` and used as the payout `referenceId`) and calls `sendPayout`. The reserve is what stops the money being spent twice. The states (`WithdrawalStatus`):

`REQUESTED` -> `SUCCEEDED` (`PayoutSucceeded`: the reserve is spent, the ledger posts debit `MEMBER_SAVINGS`, credit `PROGRAM_CASH`, `WithdrawalProcessedEvent`) -> `RETURNED` (`PayoutReturned`: the booking is reversed, the money returns to the goal);
`REQUESTED` -> `FAILED` (terminal `PayoutFailed`: the reserve returns to the goal, `WithdrawalFailedEvent` so the member is told);
`REQUESTED` -> `RETURNED` (an unclaimed payout expired: nothing was booked, the reserve returns).
A non-terminal `PayoutFailed` (UNCLAIMED) changes nothing. `FAILED` and `RETURNED` are final; every handler is idempotent, and an answer PayPal gives in the create response is applied in the same request, so the later event finds it done.

**Books at every state.** The ledger has no "payouts in flight" account (account types belong to `ledger`), so the reserve lives in savings and the books are not touched while the payout is in flight: they stay balanced, and `MEMBER_SAVINGS` equals the goal's balance plus its reserve. The posting moved from request time to success time. A transient PayPal failure rolls the whole request back (nothing reserved) and the same key retries.

Where the money goes: PayPal wallet -> `PAYPAL_ID` with the payer id from the login record, Venmo -> `EMAIL` (`IdentityFacade.payoutRecipient`); no account on file is `409 payout-recipient-missing` before anything is reserved. `wallet` is the request's, else the member's preference.

### 4. Loan disbursement is a payout saga
`ApplicationStatus` gains `DISBURSING` (migration `V5`, DB check and state machine): `APPROVED -> DISBURSING -> DISBURSED`, and `DISBURSING -> APPROVED` when the payout fails for good. `disburseApplication`:

1. locks the application; `DISBURSED` returns it (replay); anything but `APPROVED` or `DISBURSING` is `409`;
2. checks the recipient (`409 payout-recipient-missing`, nothing changed), moves to `DISBURSING`, and calls `sendPayout` with `referenceId` = the application id;
3. a payout answered `SUCCEEDED` completes in the same call; `PENDING` waits for the event; `FAILED` puts the application back to `APPROVED` with `payoutIssue` set.

Completing registers the loan through `LendingFacade.registerDisbursedLoan` with the same `disbursementRef` (`loan-<id>-disb`), which is what `/internal/loans/disbursed` calls, so the schedule, ledger postings and timers are identical and the registration is idempotent. `PayoutFailed(terminal)` and `PayoutReturned` return the application to `APPROVED` with the reason; a non-terminal failure only notes it; a return after registration is flagged (`RETURNED_AFTER_DISBURSEMENT`) and left for a person, because a registered loan is not unwound by an event. The member's cooling-off cancel only accepts `DISBURSED` and an officer cannot reject past approval, so nothing races a payout in flight.

PayPal spends a payout reference when it refuses the payout, so each retry after a terminal failure uses `<id>-a<n>` (`payout_attempts`); payout events are matched on the application's *current* reference, which also drops late reports about an earlier attempt. Every state change publishes `LoanApplicationChanged` (a whole-state snapshot, officer as the `staff:` pseudonym) inside the same transaction.

### 5. Idempotency fingerprints
`PayPalCommandExecutor` hashes the request to tell a replay from a different request under one key. Multi-entry `Map.of` requests (`CREATE_ORDER`, `REFUND`) are now records (`Fingerprints`), which serialise in declaration order, and the executor sorts map keys as a second guard. A test pins the JSON and shows two map orders hash alike.

### 6. Smaller decisions
- `CreateGoalUseCase` is `@Transactional`, so the event is published inside the transaction (regression: `SavingsEventDeliveryIT`). Pause and resume are transactional and lock the row; deposit and withdrawal already were.
- The duplicate `SavingsFacade` bean is gone: `SavingsFacadeImpl` is the only one.
- The savings controller checks the signed-in member (another member's goal is `404`); without the sign-in component (a hand-built controller in a unit test) it checks nobody and the legacy behaviour is all there is.
- The loan application term check allows 18 months (`V5`); `V4` stopped at 12 while the top rung and a TRUSTED record allow 18.
- Record levels: see [ADR 0014](0014-credit-record-projection-levels-and-signed-share-links.md). `EligibilityEngine` takes the larger of the ladder rung's ceiling and `RecordLimits.limitCents(level, ...)`, takes the higher rung's terms, and says which and why in `limitReason`. A NEW record is exactly the old behaviour. `InstalmentMissed` carries `attribution` (null, `MEMBER`, `SLA_BREACH`); `app/RecordFeed` still has to forward it.

## Settings
| Key | Default | Meaning |
| --- | --- | --- |
| `seed.savings.return-url` | `http://localhost:3000/app/savings/return` | Where PayPal sends the member after approving; `?goalId=<id>` is added |
| `seed.savings.cancel-url` | `http://localhost:3000/app/savings/cancelled` | Where PayPal sends the member who backs out |
| `seed.savings.approval-window` | `PT24H` | How long a goal waits for approval before it expires (ISO-8601 duration) |
| `seed.jobs.savings-approval-sweep-ms` | `60000` | How often the sweeper looks for goals to expire (worker role) |

Environment names: `SEED_SAVINGS_RETURN_URL`, `SEED_SAVINGS_CANCEL_URL`, `SEED_SAVINGS_APPROVAL_WINDOW`. Defaults live in the code, not in `application.yml`.

## Migrations
`savings/V3` (goal wallet, approval, reserve and change counter; `PENDING_APPROVAL` and `EXPIRED`; `withdrawal`; `credited_payment`), `lending/V5` (`DISBURSING`, `payout_issue`, `payout_attempts`, `last_officer_id`, 18-month terms).

## Alternatives considered
- **Keep `payments` calling `savings` and have `savings` publish events to `payments`.** Rejected: events do not hide a cycle from Modulith, and the dependency really ran the wrong way (payments is the lower layer).
- **A pending ledger account for the reserve.** Better books, but the account types belong to `ledger`. The reserve in savings keeps the books balanced and the posting at success; a `PAYOUTS_IN_FLIGHT` account is a small later change there.
- **Register the loan at request time and reverse it on failure.** Rejected: the receivable, schedule and timers would exist for money that never moved.
- **Reuse one payout reference for every retry.** Impossible: PayPal spends a refused reference.

## Consequences
- Money for savings and loans now moves only through `payments`, in both directions, with a stable reference for every payout and an idempotent credit for every deposit.
- The DB and domain machines enforce who may follow whom; the events are at least once and every consumer is idempotent.
- A member who never logged in with PayPal has nowhere to be paid: disbursement and withdrawal are `409` for them. Tests that disburse now sign the member in and set the simulator's payout outcome to instant success.
- `engagement` has no template for `WithdrawalFailedEvent` and `WithdrawalReturnedEvent` yet; the member sees the status on the withdrawal until it does.
- A savings webhook now reaches the goal through the inbox poller (worker or ingress role), not at receipt.

## Risks
- A `SUSPENDED` webhook that arrives after the member already resumed (out of order within seconds) would pause the goal; PayPal does not order webhooks. The goal's own state is the check, and a second `resume` fixes it.
- A payout returned after the loan was registered is flagged for a person, not unwound.
- Subscription changes (revise) may need the member to agree again at PayPal; until they do, PayPal bills the old amount.

## Sandbox checks (unverified)
| # | Question | How |
| --- | --- | --- |
| S1 | Does `PAYPAL_ID` accept the `payer_id` the login returns as a payout receiver, and does a Venmo payout accept an email? | Send one payout of each kind |
| S2 | Does revising the quantity of a savings subscription ask the payer to approve again? | `POST /v1/billing/subscriptions/{id}/revise` on an active sandbox subscription |
| S3 | Are `SUSPENDED`, `CANCELLED` and `EXPIRED` webhooks sent when the payer acts in PayPal, and with `custom_id`? | Cancel from the sandbox payer account |
| S4 | Is `-a<n>` accepted in `sender_item_id`? | Send a payout with such a reference |
