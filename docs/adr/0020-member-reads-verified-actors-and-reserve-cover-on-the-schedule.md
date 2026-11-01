# ADR 0020: Member Reads, Verified Actors on Loan and Fair-Check Routes, and Reserve Cover on the Schedule

## Status
Proposed. Builds on [ADR 0006](0006-repayment-allocation-waterfall.md) (the allocation waterfall), [ADR 0014](0014-credit-record-projection-levels-and-signed-share-links.md) (the wiring module joins modules that must not depend on each other), [ADR 0016](0016-signed-actor-tokens.md) (verified actors), [ADR 0017](0017-savings-and-loan-money-through-paypal.md), [ADR 0018](0018-consented-income-from-transaction-search-and-member-home-summary.md) (the Home page) and [ADR 0019](0019-reserve-safety-fund-and-fraud-signals.md) (reserve cover).

## Context
Four loose ends were left by earlier slices.

1. **The Home page had no production source for per-member data.** `GET /api/v1/members/me/home` composed its lending, hardship and goal sections through an interface, `MemberReads`, that had no bean outside the integration test. In production those sections answered `unavailable` and the goal list was left out.
2. **Two controllers still trusted a plain header.** `MicroloanController` and `UnderwritingController` took the member from `X-Member-Id` and the officer from `X-Staff-Id`, or from a body field, although ADR 0016 gave every other route a verified `Actor`. Worse, the desk routes of both (`/desk/loans/applications`, approve, reject, `/desk/underwriting/assessments`, decisions) checked nothing at all: a member could list every application and approve one. Disburse was already staff-only.
3. **A reserve cover never reached the loan.** ADR 0019 says reserve posts `debit LOSS_RESERVE, credit LOANS_RECEIVABLE` and publishes `CoverApproved`, and that lending must apply the amount to the schedule. Until then a covered loan's schedule and ledger disagree, and the integrity check flags it. Reserve already consumes lending's events for its exposure figure, so lending importing reserve would be a cycle, which Spring Modulith rejects even through events.
4. **A late PayPal report could undo a later state.** PayPal does not deliver webhooks in order. A `SUSPENDED` that arrives after the member resumed the goal paused it again, and nothing resumed it.

## Decision

### 1. Per-member reads on the facades, and a real `MemberReads`
Each module answers for one member by id, with a small read model in `api.dto`, member-scoped by construction (the member id is the only parameter), read-only, and without a risk level, score, signal, officer id or PayPal reference.

| Facade | Method | View |
| --- | --- | --- |
| `LendingFacade` | `eligibilityOf(memberId)` | the existing `EligibilityView`, from lending's own rules |
| `LendingFacade` | `loansOfMember(memberId)` | `MemberLoanView(loanId, status, principalCents, outstandingCents, nextDue, autopay)`, newest first; `nextDue` is the oldest instalment of the current schedule with money owed, and only for an active loan; no days past due or bucket |
| `LendingFacade` | `applicationsOfMember(memberId)` | `MemberApplicationView(applicationId, status, requestedAmountCents, termMonths, coolingOffExpiresAt, payoutIssue)`, newest first |
| `SavingsFacade` | `goalsOfMember(memberId)` | `MemberGoalView(goalId, name, status, targetCents, savedCents, monthlyCents, currency, targetDate)`, newest first |
| `HardshipFacade` | `openCaseOf(memberId)` | `MemberCaseView(caseId, loanId, status, dueDate, amountAtStakeCents, offerWaiting)` |

`openCaseOf` hides a case Seed opened from a scan and has not yet contacted the member about (status `DETECTED`, origin scan): the member was never told, and showing it would announce a risk finding. A case the member asked for is shown at once. `offerWaiting` is true in `OUTREACH_SENT` and `OFFER_VIEWED`.

`app/home` gains `FacadeMemberReads`, a `@Component` that composes the three facades and passes their answers on as given. `MemberReads` now speaks the new views, so `FacadeHomeSections` no longer reads a schedule per loan: the next payment comes in the loan view. Two small additions reach the page: the hardship section says when the payment at stake is due and how much it is, and an open application carries its `payoutIssue`. A section that fails is still an `unavailable` section and the rest of the page is unaffected.

`LendingFacadeService` takes the new `MemberViews` service lazily (an `ObjectProvider`), because the application service already takes the facade and a constructor would make a cycle.

### 2. Verified actors on the loan and fair-check routes
`MicroloanController` and `UnderwritingController` take an `Actor` like every other controller.

- A member route needs a member. The member id is the actor's. A `memberId` in a query or body is still accepted so old callers do not break, but it must be the actor's own; any other value is `404 not-found`, indistinguishable from a resource that does not exist.
- `GET /loans/applications/{id}` and `GET /underwriting/assessments/{id}` serve a member their own and answer `404` for anyone else's; an officer, checker or admin reads any; anyone else, including the service credential, gets `403`.
- Every `/desk/**` route needs `OFFICER`, `CHECKER` or `ADMIN` (`403` otherwise, `401` with no credential). The approver, rejecter, decider and disburser recorded on the row is `actor.id()`. A body `officerId` is gone from the contract and ignored if sent, so one officer cannot count as two by writing another's id.
- `X-Member-Id` and `X-Staff-Id` are read nowhere. Test requests that sent them now send `X-Seed-Member-Id` or `X-Seed-Staff-Id` with `X-Seed-Staff-Roles`, the dev headers the rest of the suite uses.

### 3. Reserve cover reaches the schedule, with no module cycle
Lending defines the use case and exposes it; the `app` wiring module carries the event, as `RecordFeed` does for the credit record (ADR 0014).

- `LendingFacade.applyReserveCover(ReserveCover)` runs `ApplyReserveCover`. `ReserveCover` carries the loan, the amount, `approvalRef`, the ledger entry id reserve already posted, the business date and the instant. `app/CoverRepaymentAdapter` listens to `reserve.api.events.ReserveEvents.CoverApproved` and calls it. Lending imports nothing from reserve.
- The money is recorded as a `RECEIPT` with source `RESERVE` (new value in the `loan_transaction.source` check, migration `V6`). Its money identity (`paypal_txn_id`) is `approvalRef`, so a second delivery finds it and changes nothing (`ALREADY_APPLIED`); the same `approvalRef` for another loan is `409 idempotency-key-reused`. The ledger entry id is stored on the transaction so the books can be followed.
- **Lending posts nothing.** `ApplyReserveCover` has no ledger collaborator. Reserve's entry already credited `LOANS_RECEIVABLE`; a second posting would count the money twice. The normal allocation runs (ADR 0006): what is due is paid first, then the rest in advance from the last instalment. The normal recalculation, retry cleanup and events follow, so the credit record sees `RepaymentReceived` with `source = RESERVE`, and autopay is told to re-sync.
- **It never applies more than the loan owes.** The amount is capped at the outstanding balance at the moment it is applied; money beyond that never becomes the member's overpayment (the fund's money is not the member's to be refunded). A loan that owes nothing (paid, or cancelled) takes nothing and answers `NOTHING_DUE`. Reserve checks the amount against the loan before it posts, so this only happens when the member paid in the window between the post and the listener. In that case the ledger credited more than the schedule took, and the result and a warning log say how many cents (`unusedCents`). Correcting the ledger is reserve's to do; see Consequences.

### 4. Out-of-order subscription reports
The savings goal keeps `subscription_state_at` (migration `V4`): the time of the latest subscription state it has taken in. `SubscriptionStateUseCase` ignores an `ACTIVE` or `SUSPENDED` report that happened before it, and moves the time forward only. A cancelled or expired subscription never comes back, so those two are applied whatever their time. Seed's own pause and resume stamp the goal with the real clock (`Instant.now()`, because PayPal stamps real time and the business clock may run ahead in a demo), so a suspension that happened before the member resumed cannot pause the goal again. A new subscription clears the stamp. Times are compared at microsecond precision, the precision the database keeps.

## Alternatives considered
- **Return the existing `LoanSummary`, `LoanApplicationView` and `CaseContext` from the member reads.** Rejected. They carry days past due, buckets, officer ids, sizing reasons, a risk level and points. A read model for a member must not be able to leak what it never holds.
- **Lending imports reserve and listens to `CoverApproved` itself.** Rejected: a cycle (reserve already imports lending's events), which Modulith rejects even through events.
- **Apply the cover by posting a repayment in lending.** Rejected: it counts the money twice in the ledger.
- **Let a cover beyond what is owed become a member overpayment.** Rejected: the member could then ask for it back from program cash.
- **Keep reading `X-Member-Id` when no actor is present.** Rejected: any caller could be any member.
- **Order subscription reports by a counter.** PayPal sends none. The report's time is all the event carries.

## Consequences
- The Home page works against production beans, not a test adapter; `MemberHomeIT` runs unchanged against `FacadeMemberReads` (its one injected failure uses a spy on `LendingFacade`).
- Tests that sent `X-Member-Id` changed that header only: `DisbursementPayoutIT`, `RecordLevelEligibilityIT`, `SeededIncomeModeIT`, `TransactionSearchIncomeIT`. The desk list in `DisbursementPayoutIT` now sends an officer, because that route now needs one.
- **Needed from payments.** `WebhookNormalizer.time()` takes the resource's `create_time` before `update_time`. For a subscription that is when it was created, not when its state changed, so every real report about one subscription carries the same time and the ordering guard cannot order them. Subscription reports should use `update_time`, falling back to the event's `create_time`. Until then the guard still protects the member's own resume (its stamp is later than any such time) and cannot undo `CANCELLED`/`EXPIRED`, but a `SUSPENDED` made in PayPal after a resume in Seed is ignored.
- **Needed from reserve.** When `unusedCents` is not zero, the ledger holds a credit to the loan's receivable that the schedule never took. Reserve should reverse that part to the fund (its own posting) or the desk must. Lending only reports it.
- A loan fully paid by the reserve becomes `REPAID` and publishes `LoanRepaid`, so the credit record counts it like any repaid loan. Whether a loan the fund paid should lift the member's record is a policy question, not decided here.
- The Postman collection still names `X-Member-Id`; it should move to the dev headers or a token.
