# F4 — Requirements

Scope: everything that happens to a loan between disbursement and closure, apart from choosing hardship options (F5), the credit record itself (F6) and reserve cover (F8). F4 provides the schedule-engine primitives that F5 calls, and emits the facts that F5, F6, F8 and F10 consume.

## 1. Policy values (versioned in the rules engine)

Every value below is stored in a `RepaymentPolicy` record with a `policyVersion`. A loan keeps the policy version it was signed under.

| Key | Value | Notes |
| --- | --- | --- |
| `graceDays` | 5 | A due instalment is *late* only when DPD > 5 |
| `minFirstDueLeadDays` | 14 | First due date is at least 14 days after disbursement |
| `dueDayRange` | 1–28 for suggestions; 1–31 accepted | Day 29–31 is clamped to month end |
| `reminderLeadDays` | 3 | "Your $150 payment is on Friday" |
| `graceNudgeDpd` | 3 | Day-3-of-grace SMS |
| `invoiceLeadDays` | 7 | Invoice mode: invoice is created 7 days before due |
| `maxAutoRetries` | 1 | One income-aware retry inside the grace period |
| `retryWindow` | DPD 2–4 | Retry outcome is known before grace ends |
| `buckets` | CURRENT, DPD_1_7, DPD_8_30, DPD_31_60, DPD_60_PLUS | Plan §7 |
| `quietHours` | 20:00–08:00 member local time | No SMS in this window |
| `closedLoanAutoRefund` | true | Money received after closure is refunded automatically |
| Interest, late fees, early-repayment fees | Always 0 | Enforced as an invariant, not a setting |

## 2. Functional requirements

Each requirement lists acceptance criteria (AC). Test IDs map in [06-test-plan.md](06-test-plan.md).

### FR-01 Generate the schedule at disbursement
On `LoanDisbursed`, create schedule version 1 with `n` equal whole-dollar instalments.
- AC1: Σ instalments = principal, exactly.
- AC2: Every instalment is a whole number of dollars. `max − min ≤ $1`. The larger amounts come last (policy `remainderPlacement = BACK`), or first if spike S3 shows that raising a subscription quantity needs payer re-approval ([03 §2](03-algorithms.md#2-generate-equal-whole-dollar-instalments)).
- AC3: Due dates are monthly on `dueDay`, clamped to month end. They never drift (31 Jan → 28/29 Feb → 31 Mar).
- AC4: The first due date is the first `dueDay` that is ≥ disbursement date + 14 days.
- AC5: Generating twice for the same loan creates no second version (idempotent on `loanId`).
- AC6: $1,200 over 8 months gives 8 × $150. $1,000 over 3 gives $333, $333, $334. $1,000 over 7 gives $142, then six × $143.

### FR-02 Suggest a due day from income timing
From the consented income summary (F3), suggest the day after money usually arrives.
- AC1: Uses a 7-day trailing-window score over day-of-month, wrapping across month ends ([03 §4](03-algorithms.md#4-income-aware-due-day-suggestion)).
- AC2: With fewer than 3 months of income data, or a flat profile, it falls back to the member's stated payday + 1, then to the 1st.
- AC3: Returns the reason in plain words, for example "Most of your Venmo sales arrive in the week before the 9th."

### FR-03 Enrol in autopay
- AC1: `POST /loans/{id}/autopay` creates a PayPal subscription on the loan-autopay plan with `quantity = next instalment in dollars`, `custom_id = loanId`, start time = first due date. It returns the approval link.
- AC2: The loan becomes `AUTOPAY` only when `BILLING.SUBSCRIPTION.ACTIVATED` arrives, or when the return-URL poll confirms it. The webhook and the poll race safely.
- AC3: A second enrolment while one is pending returns the same approval link (idempotent).

### FR-04 Invoice (manual) mode
- AC1: Members can choose `INVOICE` mode, or fall into it if the autopay subscription is cancelled.
- AC2: COB creates one PayPal invoice per instalment 7 days before due, for the instalment's remaining amount under the current version.
- AC3: If the schedule changes, open invoices for changed instalments are cancelled and reissued.
- AC4: There is never more than one open invoice per (loan, instalment).

### FR-05 One-off payments (catch-up, early, payoff, pay what you can)
- AC1: `POST /loans/{id}/payments` creates an Orders v2 order (PayPal or Venmo button) for a whole-dollar amount from $1 up to the outstanding balance.
- AC2: `GET /loans/{id}/payoff-quote` returns the outstanding balance. At 0% it is exactly principal − net repaid.
- AC3: The system can create a Payment Link for SMS for the overdue amount.
- AC4: Amounts that are not whole dollars are rejected with `422 amount-not-whole-dollars`.

### FR-06 Unified repayment ingestion
- AC1: Every money-in event for a loan (subscription sale, invoice paid, order capture, reconciler or watchdog find) is normalised into one `RepaymentReceived` command.
- AC2: Dedupe uses the PayPal transaction id (`UNIQUE`). Replaying any number of webhooks changes nothing.
- AC3: In **one transaction**: insert the repayment, post the ledger entry, re-fold the allocation, update the aging, rebuild the timers, write outbox events (ADR 0002).
- AC4: An event whose loan cannot be resolved yet is parked and retried. It is never dropped.
- AC5: `PENDING` sales (for example eCheck) are not credited. They suppress reminders and hardship triggers until they complete or fail.

### FR-07 Allocation waterfall
- AC1: The money first pays instalments due on or before `effectiveDate + 7 days`, oldest first.
- AC2: Any excess pays instalments from the last one backwards (shortening the loan).
- AC3: Any excess beyond the outstanding balance becomes a member overpayment (liability).
- AC4: Allocation is a pure, deterministic function of (schedule version, repayments ordered by effective date then id).

### FR-08 Overpayment and refunds
- AC1: The member can request a refund of an overpayment balance. The refund uses `POST /v2/payments/captures/{id}/refund` against the most recent capture that covers it.
- AC2: On a closed loan, any money received is refunded automatically within one business day.

### FR-09 Refunds, reversals and chargebacks
- AC1: `PAYMENT.SALE.REFUNDED`, `PAYMENT.CAPTURE.REFUNDED`, `*.REVERSED` and a lost dispute create a negative adjustment. The ledger posts a reversal, the allocation re-folds, aging is recomputed, and `RepaymentReversed` is emitted (F10 consumes it).
- AC2: A reversal on a `REPAID` loan reopens it to `ACTIVE`.

### FR-10 Aging and late buckets
- AC1: DPD = business date − due date of the oldest instalment with a remaining amount, floored at 0.
- AC2: Bucket mapping: 0 → CURRENT; 1–7; 8–30; 31–60; >60. `inGrace` = DPD 1–5.
- AC3: `LateBucketChanged` is emitted on every transition, from COB or straight after a payment, reversal or reschedule. `LoanCured` is emitted when the loan returns to CURRENT from a late bucket.
- AC4: `InstalmentMissed` is recorded once when an instalment's grace expires unpaid. It is never retracted.

### FR-11 Failed autopay
- AC1: `BILLING.SUBSCRIPTION.PAYMENT.FAILED` → `RepaymentFailed` event, a failure SMS, and a hardship signal to F5.
- AC2: One automatic retry is scheduled on the best income day within DPD 2–4, using subscription capture of the outstanding amount.
- AC3: No retry if the member has an open hardship case, a pending payment, or has opted out of retries.

### FR-12 Reminders
- AC1: T−3 reminder, payment-failed message, and grace-day-3 nudge (with a Payment Link and hardship options).
- AC2: Each (loan, instalment, schedule version, type) is sent at most once (dedupe key).
- AC3: Quiet hours are respected (the message is deferred, not dropped). STOP opt-out is honoured. Messages follow the member's language (en or es).
- AC4: The T−3 reminder is skipped if the instalment is already fully paid.

### FR-13 Mandate reconciliation
- AC1: After any schedule, payment or mode change, and once a day, compute the desired mandate state and compare it with the PayPal subscription (GET). Issue the fewest commands needed: revise quantity, suspend, activate, cancel.
- AC2: Running it twice issues no commands (convergence).
- AC3: Drift that cannot be fixed (for example, the billing date is more than 3 days off) raises `MandateDriftDetected`. That collection cycle falls back to a Payment Link.

### FR-14 Missing-payment watchdog
- AC1: At due + 1 day, if an active mandate has neither a sale nor a failure recorded, read `GET /v1/billing/subscriptions/{id}/transactions` and ingest what PayPal says.
- AC2: Nightly, every PayPal transaction with `custom_id = loan-*` must have a repayment row, and every repayment row must have a PayPal transaction. Rows found only on PayPal are ingested with `source = RECONCILER`.

### FR-15 Loan closure
- AC1: When the outstanding balance reaches 0, the loan becomes `REPAID`. The mandate is cancelled, open invoices are cancelled, and `LoanRepaid` is emitted (F6 unlocks the next level).
- AC2: A late autopay charge that lands after closure is auto-refunded (FR-08 AC2).

### FR-16 Schedule read models
- AC1: The member and the desk can read the current schedule with derived status per instalment (`SCHEDULED`, `DUE`, `PAID`, `PARTIALLY_PAID`, `IN_GRACE`, `LATE`, `PREPAID`).
- AC2: All versions can be read, with a diff showing which instalments moved and why.
- AC3: A Bryntum-shaped timeline endpoint returns resources, events and the previous version as "ghost" events.
- AC4: SSE pushes `ScheduleChanged` and `InstalmentStatusChanged` to open screens.

### FR-17 Desk portfolio views
- AC1: AG Grid server-side row model endpoint for loans with sort, filter, group by bucket, and pagination.
- AC2: Portfolio at risk (PAR30), collection rate, first-attempt autopay success rate, cure rate.
- AC3: Monthly roll-rate matrix (bucket at month start → bucket at month end).

### FR-18 Close of business
- AC1: One COB per business date, in order, never skipping a date. Catch-up runs each missing date in sequence.
- AC2: Sharded, resumable and idempotent. Re-running a completed date changes nothing.
- AC3: The demo clock advances the business date and triggers catch-up COBs. Every screen labels the demo date.

### FR-19 Reschedule primitives (for F5)
- AC1: `moveDate(seq, days ≤ 14)`, `deferToEnd(seq)`, `reamortize(extraMonths ≤ 6, floor $25)`. Each returns a new version.
- AC2: Every primitive preserves Σ = principal. Re-folding the same money history leaves the outstanding balance and the overpayment unchanged, never makes the member more overdue, and keeps money paid in advance in advance (PROP-RSC-01).
- AC4: A deferred instalment always lands after today.
- AC3: The previous version is kept unchanged.

### FR-20 Agent read tools
- AC1: `get_repayment_schedule`, `get_loan_aging`, `list_repayments` are read-only, scoped to the member in the session, and return summaries without PayPal payer ids.

### FR-21 Audit
- AC1: Every version, repayment, adjustment, mode change and refund records who or what caused it (member, staff id, webhook event id, job run id) and the policy version.

## 3. Non-functional requirements

| ID | Requirement | Target |
| --- | --- | --- |
| NFR-01 | Webhook received → ledger posted | p99 ≤ 5 s |
| NFR-02 | Ingress acknowledgement to PayPal | p99 ≤ 300 ms (verify signature, insert into inbox, return 200) |
| NFR-03 | `GET /loans/{id}/schedule` | p95 ≤ 100 ms at 50 concurrent users |
| NFR-04 | Engine calls (generate, allocate, age, timers) | ≤ 5 ms for a 36-instalment loan with 100 repayments |
| NFR-05 | COB for 100,000 active loans | ≤ 15 min worst case (every loan has a timer); ≤ 2 min typical |
| NFR-06 | Reconciliation drift (ledger vs PayPal) | 0 at every nightly run in the demo |
| NFR-07 | Delivery semantics | At-least-once everywhere; exactly-once *effect* via unique keys |
| NFR-08 | Availability under PayPal outage | Reads and COB continue; PayPal commands queue and retry with jittered backoff |
| NFR-09 | Security | Members see only their own loans (404 otherwise); internal jobs need a service token; webhooks are signature-verified |
| NFR-10 | Privacy | No raw transactions or payer emails in Elastic or agent tools; PII masked in logs |
| NFR-11 | Accessibility of messages | SMS at about grade-6 reading level, in en and es |

## 4. Invariants (checked by tests and by a nightly invariant job)

| ID | Invariant |
| --- | --- |
| INV-1 | For every schedule version: Σ instalment amounts = principal. Enforced by a deferred constraint trigger. |
| INV-2 | Ledger receivable balance for the loan = principal − Σ net repayments applied = Σ remaining amounts of the current version's instalments. |
| INV-3 | Each PayPal money transaction for a loan maps to exactly one repayment or adjustment row and exactly one journal entry. |
| INV-4 | 0 ≤ paid(i) ≤ amount(i) for every instalment. Σ paid + overpayment = Σ net received. |
| INV-5 | No interest and no fees: the member never owes more than principal. |
| INV-6 | A loan with outstanding = 0 has no `ACTIVE` mandate after at most one reconcile cycle. |
| INV-7 | COB dates are contiguous; each (loan, business date) has its effects applied once. |
| INV-8 | Outstanding ≥ 0; receivables never go negative (excess goes to `member_overpayment`). |
| INV-9 | `InstalmentMissed` facts are append-only. |
| INV-10 | Only the `payments` module calls PayPal write APIs; only `ledger` writes journal tables. |

## 5. Out of scope for F4

- Choosing and approving hardship options (F5). F4 provides the primitives and the signals.
- Computing the credit record and levels (F6). F4 emits `InstalmentMissed`, `LoanRepaid` and `LoanCured`.
- Reserve cover at 60+ days (F8). F4 emits the `DPD_60_PLUS` transition.
- Interest-bearing products, fees, multi-currency. USD only, 0% only.
