# ADR 0019: Reserve Safety Fund and Fraud Signals

## Status
Proposed. Extends [ADR 0002](0002-ledger-transaction-boundary.md), [ADR 0003](0003-agent-proposal-governance.md) and [ADR 0009](0009-ai-proposes-code-computes-human-approves.md).

## Context
Two modules were empty scaffolds, `reserve` and `fraud`, and each has a hard boundary to respect.

**Reserve.** Seed lends at 0% to people with no credit score. Some of them will have a bad month. The hardship safety net moves dates, but sometimes a date is not enough and a small bridge is. Donors (members, the public) are willing to fund that bridge through PayPal Donate, but only if they can see what the fund holds and what it pays, and only if their own identity is not exposed. A fund that pays people would be a different, regulated product; Seed's fund must pay loans and nothing else.

**Fraud.** A loan programme for the underbanked attracts a small number of abusers (one payer behind many accounts, money in and straight out, a payout redirected just before disbursement). The same programme serves honest people who share a household PayPal account, fail a payment in a hard month, or open two goals for two children. A black-box score, or any automatic block, would punish exactly the people Seed exists to serve, and could not be explained to a member who disputes it.

## Decision

### Reserve

**The ledger holds the money; reserve holds the facts.** A gift posts `debit PROGRAM_CASH (gross minus fee), debit PAYPAL_FEES (fee), credit LOSS_RESERVE (gross)` through `ledger.api` in the caller's transaction (MANDATORY, ADR 0002). A cover posts `debit LOSS_RESERVE, credit LOANS_RECEIVABLE(loan)`. No cash moves, because donated money already sits in the program's PayPal balance, so the entry only reassigns it from the fund to the loan. The source keys are `reserve-donation:<captureId>` and `reserve-cover:<coverRequestId>`; the ledger refuses to post either twice. A single row, `reserve.fund`, holds the counters the public summary reads. It is updated in the same transaction, under a row lock, by a pure `FundState` that refuses a negative balance and arithmetic overflow, and a database check keeps `balance = donated - paid out`. The row is the lock every money movement starts with, so two covers cannot spend the same cent and two deliveries of one capture queue up.

**Donations.**
- `ReserveFacade.recordDonation(captureId, amountCents, donorDisplayName, anonymous)` is idempotent on the PayPal capture id. The same capture with another amount is `idempotency-key-reused` (409).
- The public page asks `POST /api/v1/reserve/donations/intent` first. The server stores the donor's choice and returns a `customId` of the form `reserve-donation:<uuid>`. The page gives it to PayPal as `custom_id`; the capture webhook returns it, and `recordDonationForCustomId` resolves the choice. No PayPal credential leaves the server. An unknown or foreign custom id records an anonymous gift.
- The donor is anonymous by default. A name is stored only when the donor chose to be shown, after markup, links and control characters are stripped, at most 40 characters. A database check forbids a name on an anonymous row. No email, payer id or address is stored.

**Public transparency.** `GET /api/v1/reserve/summary` needs no login: balance, total donated, number of gifts (a repeat donor counts each time, because Seed keeps no donor identity to deduplicate on), total paid out, members covered, open exposure, coverage ratio in basis points, and the latest opted-in names. Open exposure is the sum of outstanding balances kept from lending's events (`ScheduleGenerated`, `RepaymentReceived`, `RepaymentReversed`, `LoanReopened`, `LoanRepaid`). Each event states the balance itself and carries a time-ordered id, so a repeated or reordered event cannot drift the figure. The ratio is rounded down.

**Cover requests.** An officer raises one for a member's active loan (`POST /api/v1/desk/reserve/cover-requests`). Limits (`seed.reserve.cover.*`): 500 dollars per request and 1,000 dollars per member per calendar year by default, counting approved and pending requests. The amount cannot exceed what the loan still owes, and the fund must hold at least the amount. The request is a governance subject. Governance runs the maker-checker decision exactly as hardship does (`openApprovalRequest`, then `decide`, a different checker, the member never decides, separation of duties also enforced by a database trigger). The decision that closes the request as approved posts the ledger entry in the same transaction and publishes `CoverApproved`. Before any approving signature is recorded, the fund balance and the loan are checked again, so officers never sign for money that is not there. If governance approves through another path, a listener on `ApprovalDecided` brings the cover to the same end; if the money or the loan is no longer there it records `FAILED` and posts nothing. A rejection, void or expiry closes the request as `REJECTED`. One request may be open per loan.

**What lending must do with `CoverApproved`.** The ledger already shows the loan's receivable reduced. Lending applies `amountCents` to the schedule as a repayment with source `RESERVE`, idempotent on `approvalRef`, and must **not** post to the ledger again. This is the one change needed outside the reserve module, and until it exists a covered loan's schedule and its ledger disagree (the integrity check flags it).

**What payments must publish.** Today `PAYMENT.CAPTURE.COMPLETED` with a `custom_id` that is not `loan:<id>` is ignored as "not for a Seed loan". Payments needs to publish a `DonationCaptured` event (event id, PayPal transaction id, `custom_id`, gross and fee cents, time) for a capture whose `custom_id` starts with `reserve-donation:`, and reserve then listens and calls `recordDonationForCustomId`. Until then the facade is the contract.

### Fraud

**Facts, signals, flags.** Event-fed rules read facts, not people. A fact is something that happened (`GOAL_CREATED`, `DEPOSIT`, `WITHDRAWAL`, `LOAN_DISBURSED`, `PAYMENT_FAILED`, `PAYER_SEEN`, and three that only `FraudFacade.observe` can feed today: `ACCOUNT_CREATED`, `APPLICATION_SUBMITTED`, `PAYOUT_RECIPIENT_CHANGED`). Facts are stamped with the business clock at ingestion, so the rules compare like with like even when the demo clock runs ahead. A stable identifier (a PayPal payer id, a payout recipient) is turned into an HMAC-SHA256 with a salt created by the migration and kept in the fraud schema; only the hash is stored, and officers see its first 8 characters as a tag that matches two facts without revealing anything. The facts table is append-only.

Seven pure rule classes, each with a code, a weight, a plain sentence and the numbers and fact ids behind it. The set is versioned (`fraud-rules-v1`).

| Rule | Fires when | Weight |
| --- | --- | --- |
| `GOAL_VELOCITY` | 4 or more new goals in 24 hours | 20 |
| `APPLICATION_VELOCITY` | 2 or more applications in 7 days | 30 |
| `SHARED_PAYER` | the same payer (by hash) pays for another member too | 40 |
| `DEPOSIT_CHURN` | at least 80% of a withdrawal of 25 dollars or more arrived in the previous 48 hours; once, or repeated | 15, 35 |
| `NEW_ACCOUNT_LOAN` | an application or disbursement within 72 hours of the account starting | 35 |
| `REPEATED_FAILED_PAYMENTS` | 3 or more failed payments in 30 days | 10 |
| `PAYOUT_RECIPIENT_CHANGE` | the payout recipient changed in the 72 hours before a disbursement | 40 |

The score of a member is the sum, over rules, of the heaviest signal each rule raised. A flag opens when the score reaches 40 (`seed.fraud.flag-threshold`): one strong signal, or several mild ones together. Failed payments weigh 10 on purpose: a member who cannot pay is usually a member in hardship, and no failed payment alone, nor a few mild signals, can flag them.

**A flag is advice.** Statuses are `OPEN`, `REVIEWED_CLEAR`, `CONFIRMED`. Nothing in Seed reads a flag to refuse, limit or price anything. It is input for officers and for the fair-check queue, where a person decides. A signal is stored once per rule, facts and numbers (a fingerprint), so the same evidence can never raise a second flag after an officer cleared the first, while new evidence can. One flag is open per member.

**Review and audit.** An officer (`OFFICER` or `ADMIN`) clears or confirms a flag, once, with an idempotency key; confirming needs a note. The decision is written to the governance audit chain (the note stays in the fraud schema; the chain is permanent), and `FraudFlagRaised` and `FraudFlagReviewed` are published. `GET /api/v1/desk/fraud/flags/{id}/evidence` returns every signal with the facts it was computed from, which is the pack for a dispute.

**Fair and explainable.** No machine learning, no model call, no protected attribute, no name, email, location or device. Every rule can be redone by hand from the evidence on screen. Reading is by role: officers, checkers and admins.

**Postgres, not Elastic.** The signal store is plain tables. At this volume a window query over an indexed table is cheap, and a search engine would add a second store to keep consistent. Elastic can read the tables later without changing the rules.

**Concurrency.** Observations of one payer queue behind a transaction-scoped advisory lock, and the affected members are locked in id order, so two members first seen with one payer at the same moment cannot each miss the other and two evaluations cannot deadlock.

## Alternatives considered
- **Reserve pays the member.** Rejected. It turns a loan programme into a grant or insurance product, needs a payout path outside the loan, and removes the evidence that the money helped the loan.
- **Cover through program cash** (reserve to cash, then lending's normal repayment posting). Rejected. It would say that cash moved when it did not, and make the books disagree with PayPal's balance.
- **Show donors by default.** Rejected. A name is shown only on an explicit choice, and the choice is stored before the donor leaves for PayPal.
- **Count distinct donors.** Rejected. It needs a stable donor identifier, which is exactly the data Seed refuses to hold.
- **A fraud score from a model, or auto-blocking.** Rejected for the reasons in ADR 0009: it cannot be explained to a member, there is no outcome history to train on, and a wrong block costs an honest member their access to a loan.
- **Signals only in memory or a search engine.** Rejected. Signals are evidence for disputes and must outlive a restart, and Postgres already gives idempotency, triggers and transactions.

## Consequences
- The fund's balance, the ledger and the public figure agree by construction and by test, and a covered loan's receivable falls without a person receiving money.
- A cover needs two people and is bounded per request and per member per year; the largest loss one officer pair can approve is capped by configuration.
- Open exposure and coverage are approximate until lending applies `CoverApproved` and the payments module publishes donations; both gaps are listed above.
- Facts not yet carried by an event (account creation, applications, payout recipient changes) only enter through `FraudFacade.observe`. When identity publishes `MemberRegistered` and lending or payments publish the other two, the listener gains three methods and the rules stay as they are. Email-domain patterns are not possible until an event carries a domain.
- The rules are conservative and blunt on purpose. They will miss clever fraud, and officers will see some false flags; each is cleared with a note, and clearing is a first-class outcome.
