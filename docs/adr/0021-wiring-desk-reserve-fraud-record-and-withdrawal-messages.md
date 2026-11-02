# ADR 0021: Wiring the Desk, Reserve, Fraud, Credit Record and Withdrawal Messages to the Live System

## Status
Accepted. Closes the gaps ADR 0014, 0015 and 0019 listed as "not wired yet", now that lending, underwriting and payments publish the events those decisions waited for ([ADR 0017](0017-savings-and-loan-money-through-paypal.md), [ADR 0018](0018-consented-income-from-transaction-search-and-member-home-summary.md)).

## Context
Four modules were built and tested against synthetic inputs while the modules they depend on were still being written:

- `desk-query` projected loan applications and fair-check assessments from snapshots its own test fed by hand ([ADR 0015](0015-desk-read-models-keyset-grids-and-replayable-projections.md)).
- `reserve` had a facade for donations but nothing called it ([ADR 0019](0019-reserve-safety-fund-and-fraud-signals.md)).
- `fraud` accepted three facts only through `FraudFacade.observe` (an account being created, a loan application, a change of payout recipient), and without the first its `NEW_ACCOUNT_LOAN` rule could mistake the first thing Seed saw for the account's age.
- The credit record ([ADR 0014](0014-credit-record-projection-levels-and-signed-share-links.md)) counted every `InstalmentMissed`, including a miss caused by our own late decision on a hardship case ([ADR 0011](0011-hardship-case-lifecycle-consent-hold-and-standing.md)).
- A withdrawal PayPal could not deliver ended with a `WithdrawalFailedEvent` or `WithdrawalReturnedEvent` that nobody listened to, so the member found out by looking.

## Decision

### Desk queues fill from events
`lending` publishes `LoanApplicationChanged` on every application state change (now including `DISBURSING`, the state while PayPal has not confirmed the payout) and `underwriting` publishes `AssessmentChanged`. `DeskProjectionListeners` gains one method for each, which maps the record field for field onto `ApplicationProjector.Snapshot` and `AssessmentProjector.Snapshot`. Both are whole-state snapshots guarded by `changedAt`, so a repeated, late or reordered delivery cannot corrupt a queue and the publication-log replay of ADR 0015 covers them like every other projection. `DISBURSING` is accepted as a status filter value and, like `APPROVED`, is not an open status: no officer has anything to do on it, so it has no SLA and leaves the workload counts.

`desk-query` now depends on `underwriting` (events only). There is no cycle: underwriting imports savings, identity and payments.

**One lock per member in the projectors.** The application, its fair check and the member's deposits arrive on separate threads, and each reads what the others write (an application row reads the streak and the assessment outcome; an assessment writes the outcome onto the application row; a deposit writes the streak onto the member's application rows). With synthetic input this could not happen, with live events it can: one handler reads just before the other commits and leaves a stale streak or outcome. `ApplicationViewRepository.lockMember` takes a transaction-scoped advisory lock on the member, and the three projectors take it first.

### Donations reach the fund
`payments` publishes `DonationCaptured` for a `PAYMENT.CAPTURE.COMPLETED` whose `custom_id` starts with `reserve-donation:`. `ReserveEventListener` calls `ReserveFacade.recordDonationForCustomId(customId, paypalTxnId, gross, fee)`, which is idempotent on the transaction id (the same capture under a new webhook id, or the same webhook twice, records one gift). `reserve` now depends on `payments` (events only).

- A custom id of ours that names no known intent, or that is malformed after the prefix, is still recorded, as an anonymous gift. The ADR 0019 contract is that the money did arrive in the program's PayPal account, and leaving it out of the fund would put the fund and the PayPal balance permanently out of step. The listener logs the case with the transaction id only, never a donor choice.
- An event that cannot be a gift at all (a custom id that does not start with the prefix, a non-positive amount, a fee at or above the gross) is dropped with a warning instead of throwing. A listener that always throws stays in the publication log and is retried forever; the money in that case is a PayPal problem for an officer, not something the fund can book.
- An amount above `seed.reserve.donation.max-cents` still fails and stays incomplete in the log, so a person sees it.

### Fraud is fed by the wiring module
`FraudFeed` (in `app`, the wiring module, like `RecordFeed`) listens to `identity.MemberRegistered`, `identity.MemberProfileChanged` and `lending.LoanApplicationChanged` and calls fraud. Fraud already depends on lending, payments and savings, so none of them can depend on it; `app` can depend on all.

| Fact | Source | Key | Stamped |
| --- | --- | --- | --- |
| `ACCOUNT_CREATED` | `MemberRegistered` | `account:<member>` | the event's own time (identity's business clock) |
| `APPLICATION_SUBMITTED` | `LoanApplicationChanged` with status `SUBMITTED` only | `application:<application>` | the business clock at ingestion (lending stamps applications with wall-clock time, which a demo clock does not share) |
| `PAYOUT_RECIPIENT_CHANGED` | `MemberProfileChanged`, only when the destination differs | `destination:<event>` | the event's time |

With `ACCOUNT_CREATED` present, the rule uses the real registration as the account's start (`accountStartBasis: REGISTERED`) and the false positive of ADR 0019 goes away: a 30-day-old account whose first goal and first application come on the same day no longer looks new.

**Payout recipient.** Nothing publishes a payout's recipient. The only place the destination changes is the member's wallet preference (PayPal wallet, paid to the payer id, or Venmo, paid to the email, ADR 0017), announced by `MemberProfileChanged`. That event also fires for a change of language, which must never be a signal. So fraud keeps the hash of each member's current destination (`fraud.payout_destination`, migration `V3`) and `FraudFacade.payoutDestination(member, reference, at, sourceKey)` compares: the first destination seen is a baseline and records nothing, the same one again records nothing, a different one records a `PAYOUT_RECIPIENT_CHANGED` fact. The adapter hands over `PAYPAL:<payerId>` or `VENMO:<email>`; fraud hashes it with the same salted HMAC as every other reference, and neither value is stored, logged or returned. `observe` keeps its meaning (a caller states that a change happened), so every existing scenario still works.

### The credit record excludes our own late decisions
`RecordFeed` drops an `InstalmentMissed` whose `attribution` is `SLA_BREACH` before it reaches the record. `MEMBER` and an unattributed miss are recorded as before. The record keeps no attribution field of its own, so excluding the fact at the feed is the whole rule: the record never counts a miss it would have to un-count, which is also what ADR 0004 (facts are append-only) requires. This replaces the last open point in ADR 0014's consequences.

### Withdrawal messages
`SavingsMessageTriggers` in `engagement` listens to `WithdrawalFailedEvent` and `WithdrawalReturnedEvent` and sends one calm message per withdrawal and outcome through the existing queue (`ReminderService.notifyAboutGoal`, the sibling of `notify` used for hardship): the in-app copy always, the SMS only when the member has a phone and has not said STOP, deferred past quiet hours (20:00 to 08:00 local), deduplicated by `withdrawal:<id>:failed|returned` so a redelivered event never texts twice. The text is `SavingsMessages` (English and Spanish): the amount from the event's structured field, "your money is safe", "no fees", "you can try again in the app". PayPal's reason code is never put in a message.

A reminder named a loan, and a withdrawal has a goal, so migration `V4` makes `loan_id` optional, adds `goal_id`, requires one of the two, and adds the two types. `ReminderRepository.latestLoanOf` now ignores messages without a loan, so a goal message never hides the loan an SMS reply (`HELP`) should find.

### Ledger note (no change made)
`PAYOUTS_IN_FLIGHT` is not needed by this slice and nothing here asks `ledger` for it. Between a payout request and its success the books are balanced but `PROGRAM_CASH` still shows money PayPal has already taken out of the balance (ADR 0017 posts at success, the reserve lives in savings). That window is seconds for an instant payout and up to the unclaimed period (days) otherwise. It matters only for an automated reconciliation of `PROGRAM_CASH` against PayPal's balance, where the in-flight total is a known reconciling item. If that reconciliation is built, add an asset account `PAYOUTS_IN_FLIGHT`, post `debit PAYOUTS_IN_FLIGHT, credit PROGRAM_CASH` at request, and replace it at success with `debit MEMBER_SAVINGS (or LOANS_RECEIVABLE), credit PAYOUTS_IN_FLIGHT`, and reverse it on a terminal failure. The reserve fund has no payouts, so it is unaffected.

## Alternatives considered
- **Let fraud and credit record import the producing modules' events directly.** Rejected for credit record by ADR 0014 (a cycle with lending). For fraud the imports of lending, payments and savings already exist; identity is added through the adapter so fraud does not grow another dependency and the three new facts stay in one reviewed place.
- **Treat every `MemberProfileChanged` as a recipient change.** Rejected: a member who switches the app to Spanish would gain a signal, and ADR 0019 promises that honest members are not flagged by routine actions.
- **Add an `attribution` field to the record fact.** Rejected: it needs a change in `credit-record` for no benefit, because a miss that is not ours is never needed in the record.
- **Ignore a donation whose custom id names no intent.** Rejected: see above, the books would no longer match PayPal.
- **Retry a failing donation event forever.** Rejected for amounts that can never become a gift; kept for transient failures.
- **A separate `goal_message` table in engagement.** Rejected: the queue's deduplication, quiet hours, opt-out and dispatch already work on `reminder`; a second table would repeat them.

## Consequences
- The application and fair-check queues are live. The direct `apply` calls remain only for the synthetic rows `DeskQueryIT` uses to create ties for paging tests.
- `NEW_ACCOUNT_LOAN` is based on the registration for every member who registered after this change. A member who registered before it has no `ACCOUNT_CREATED` fact and keeps the old "first activity seen" fallback.
- A destination is compared only after a baseline exists: a member whose wallet changed before their registration event was processed has the new wallet as their baseline, and that one change is not seen (the two events are handled on separate threads; the window is the seconds after sign-up).
- The wallet chosen on a single loan application (the request can override the preference) is not seen, and neither is an email change at a later PayPal sign-in, because identity publishes neither. Both would need an event from identity or payments; this ADR does not add one.
- `credit-record` still counts the late payment of an instalment whose miss was excluded, if the member pays it after grace; it is a late payment (visible, never a demotion), not a miss, and a hardship plan that moves the due date makes the payment on time.
- The Spanish texts have not been reviewed by a native speaker.

## Migrations
`fraud/V3__payout_destination.sql`, `engagement/V4__savings_messages.sql`.

## Tests
`DeskQueryIT` (queues through the real events), `ReserveIT` (webhook to the fund balance, idempotent, anonymous fallback, impossible amounts), `FraudIT` (registration, application, false positive, new account, wallet change, language change, hash-only storage), `CreditRecordIT` (`SLA_BREACH` excluded, `MEMBER` and unattributed counted, duplicates harmless), `WithdrawalMessagesIT` (failed, returned, no phone, STOP, Spanish, quiet hours, loan lookup), and unit tests for each listener and for the new repository.
