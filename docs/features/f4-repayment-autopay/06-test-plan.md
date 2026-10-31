# F4 — Test Plan and Test Cases

## 1. Levels and tools

| Level | Scope | Tool | Runs |
| --- | --- | --- | --- |
| Property | `engines/schedule` invariants over random inputs | jqwik (1,000 tries per property; 10,000 nightly) | Every build |
| Unit (example) | Engine worked examples, rules, mappers | JUnit 5 | Every build |
| Module integration | One module with real Postgres; one transaction per use case | Testcontainers, Spring Modulith `@ApplicationModuleTest` | Every build |
| Cross-module scenario | Event flows lending ↔ payments ↔ ledger ↔ engagement | Spring Modulith `Scenario` API, PayPal stub (WireMock) | Every build |
| Architecture | Module and purity rules | ArchUnit, Spring Modulith `verify()` | Every build |
| Contract | API vs OpenAPI; events vs JSON Schema; PayPal requests vs recorded shapes | Postman CLI / Newman, json-schema-validator | Every PR |
| Chaos | Duplicate, reordered, lost webhooks; PayPal 5xx and timeouts | Postman Simulator | Every PR (preview environment) |
| Sandbox end to end | Real PayPal sandbox objects | Playwright + Postman Flows | Nightly and before the demo |
| Performance | Engine micro-benchmarks; COB at 100k; API latency | JMH, Postman performance, k6 | Nightly |
| Agent evals | Coach and Guardian number accuracy | Astropods evals | On prompt change |

**Conventions:** `PROP-*` property, `TC-*` example or integration, `C*` chaos, `E2E-*`, `PERF-*`, `EVAL-*`. All dates are business dates (`America/Los_Angeles`) unless a UTC timestamp is shown. The tests use `FakeBusinessClock` (`platform/testing`).

## 2. Schedule generation

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-GEN-01 | P = $1,200, n = 8 | generate | 8 × $150 |
| TC-GEN-02 | P = $1,000, n = 3, BACK | generate | $333, $333, $334 |
| TC-GEN-03 | P = $1,000, n = 7, BACK | generate | $142, then 6 × $143 |
| TC-GEN-04 | P = $1,000, n = 7, FRONT | generate | 6 × $143, then $142 |
| TC-GEN-05 | P = $5, n = 6 | generate | Rejected: `principal-less-than-instalment-count` |
| TC-GEN-06 | P = $1,200.50 | generate | Rejected: `principal-not-whole-dollars` |
| TC-GEN-07 | `LoanDisbursed` delivered twice | listener | One schedule version; second delivery is a no-op |
| PROP-GEN-01 | Any P in [$100, $5,000], n in [3, 18], P ≥ n | generate | Σ = P; every amount > 0 and a multiple of 100 cents; max − min ≤ $1; max = ⌈P/n⌉ |
| PROP-GEN-02 | Same | generate | BACK: non-decreasing; FRONT: non-increasing |

## 3. Due dates

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-DUE-01 | dueDay 31, first month Jan 2027 | generate 4 | 31 Jan, 28 Feb, 31 Mar, 30 Apr |
| TC-DUE-02 | dueDay 30, first month Jan 2028 | generate 3 | 30 Jan, 29 Feb (leap), 30 Mar |
| TC-DUE-03 | Disbursed 2026-11-30, dueDay 5 | first due | 2027-01-05 (5 Dec is only 5 days later, under 14) |
| TC-DUE-04 | Disbursed 2026-11-10, dueDay 25 | first due | 2026-11-25 (15 days ≥ 14) |
| TC-DUE-05 | Disbursed 2027-03-13 (DST change on 14 Mar), dueDay 14 | generate | 2027-04-14, …; no off-by-one |
| PROP-DUE-01 | Any dueDay 1–31, any disbursement in 2026–2030, n ≤ 18 | generate | Dates strictly increase; one per consecutive month; day = min(dueDay, month length); first ≥ disbursement + 14 |

## 4. Due-day suggestion

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-DDS-01 | Ana: Venmo sales cluster on the 6th–8th, 6 months | suggest | 9, with reason naming the week before the 9th |
| TC-DDS-02 | Biweekly pay on the 1st and 15th, equal amounts | suggest | 2 (days 2–8 and 16–22 tie; ties go to the smallest). A circular mean would wrongly give the 8th |
| TC-DDS-03 | Income lands on the 30th or 31st | suggest | 1 (the window wraps across the month end) |
| TC-DDS-04 | Only 2 months of data, stated payday 12 | suggest | 13, reason "based on the payday you told us" |
| TC-DDS-05 | Income spread evenly every day | suggest | Fallback (strength < 0.35) |
| PROP-DDS-01 | Any profile | suggest | Result is in 1–28 |

## 5. Allocation waterfall

Loan for these cases: $1,200, 8 × $150, due on the 9th, first due 2026-12-09.

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-ALC-01 | Nothing paid | $150 on 2026-12-09 | s1 PAID, `fullyPaidOn` = 12-09; outstanding $1,050 |
| TC-ALC-02 | Nothing paid | $150 on 2026-12-04 (5 days early) | s1 PAID (inside the 7-day window) |
| TC-ALC-03 | Nothing paid | $150 on 2026-11-29 (10 days early) | s8 PAID (prepayment); s1 still due |
| TC-ALC-04 | s1 due | $100 then $50 on the due date | After the first: PARTIALLY_PAID, remaining $50; after the second: PAID |
| TC-ALC-05 | s1, s2 overdue | $300 on 2027-01-20 | s1 and s2 PAID, oldest first |
| TC-ALC-06 | s1 due | $200 on the due date | s1 PAID; $50 to s8 |
| TC-ALC-07 | s2 paid by autopay | Manual $150 the same day (double pay) | s8 PAID; next autopay quantity unchanged; outstanding −$150 |
| TC-ALC-08 | Outstanding $150 | $200 | Outstanding 0; overpayment $50 |
| TC-ALC-09 | s1 paid on time | Reversal of that payment on 2026-12-20 | s1 unpaid; dpd = 11; bucket DPD_8_30 |
| TC-ALC-10 | Two payments dated 12-09 and 01-09 | Ingested in reverse order | Final allocation identical to in-order ingestion |
| TC-ALC-11 | s1, s2 paid; version 2 = deferToEnd(s3) | Re-fold | Σ paid unchanged; s1, s2 still PAID; new s9 unpaid |
| PROP-ALC-01 | Random schedule and 0–50 payments and reversals | allocate | Σ paid + overpayment = Σ net |
| PROP-ALC-02 | Same | allocate | 0 ≤ paid[i] ≤ amount[i] |
| PROP-ALC-03 | Same, plus one extra payment | allocate | Outstanding does not increase; dpd does not increase |
| PROP-ALC-04 | Same events, shuffled input order | sort, then allocate | Identical result |
| PROP-ALC-05 | Σ net ≤ P | allocate | Outstanding = P − Σ net; overpayment = 0 |

## 6. Aging and buckets

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-AGE-01 | s1 due 12-09, unpaid | age at 12-09 | dpd 0, CURRENT |
| TC-AGE-02 | Same | age at 12-10 | dpd 1, DPD_1_7, inGrace |
| TC-AGE-03 | Same | COB 12-14 then 12-15 | 12-14: inGrace; 12-15: late, `InstalmentMissed(s1)` once |
| TC-AGE-04 | Same | age at dpd 7, 8, 30, 31, 60, 61 | DPD_1_7, DPD_8_30, DPD_8_30, DPD_31_60, DPD_31_60, DPD_60_PLUS |
| TC-AGE-05 | Loan in DPD_8_30 | Full catch-up payment | CURRENT straight away, `LateBucketChanged(cause=PAYMENT)`, `LoanCured` |
| TC-AGE-06 | s1, s2 overdue | Pay s1 only | dpd measured from s2's due date |
| TC-AGE-07 | s3 in grace (dpd 3) | deferToEnd(s3) approved | CURRENT; no `InstalmentMissed` for s3 |
| TC-AGE-08 | s3 missed (dpd 10) | deferToEnd(s3) approved | CURRENT; the existing `InstalmentMissed(s3)` remains (INV-9) |
| TC-AGE-09 | s1 overdue, a `PENDING` sale covers it | COB at dpd 3 | `pendingCover = true`; no grace nudge sent |
| PROP-AGE-01 | Any loan, no money events | age on consecutive days | dpd rises by exactly 1 per day once positive |
| PROP-AGE-02 | dpd 0–400 | bucket | Total function; monotone in dpd |

## 7. Timers and close of business

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-TMR-01 | New 8-instalment AUTOPAY loan, asOf = disbursement | timers | For each instalment: REMIND (due − 3), WATCHDOG (+1), NUDGE (+3), EXPIRY (+6); boundaries for s1 at +1, +8, +31, +61 |
| TC-TMR-02 | Any loan | Rebuild timers twice | Identical rows |
| TC-TMR-03 | REPAID loan | timers | Empty |
| TC-TMR-04 | INVOICE-mode loan | timers | CREATE_INVOICE at due − 7, no WATCHDOG |
| TC-COB-01 | COB(D − 1) is RUNNING | start(D) | 409 `previous-cob-incomplete` |
| TC-COB-02 | COB(D) exists | start(D) again | Same run returned; no new rows |
| TC-COB-03 | 5,000 loans; the shard process is killed after 1,234 loans | Workflows retries the shard | Event multiset identical to an uninterrupted run; no duplicate reminders or commands |
| TC-COB-04 | A payment transaction holds the loan lock | shard(D) | Loan skipped, then processed in `finish(D)` |
| TC-COB-05 | Demo clock +120 days | advance | 120 contiguous COB runs, each COMPLETED; events in date order per loan |
| TC-COB-06 | One loan throws (corrupted fixture) | COB(D) | Others processed; run DEGRADED; Slack alert; re-running after the fix completes it |
| TC-COB-07 | **Differential:** 1,000 random loans with random payments, reversals and reschedules over 365 days | Run COB in `timers` mode and in `scan` mode | Identical events per business date |
| TC-COB-08 | COB(D) COMPLETED | Run shard(D) again | Zero new effects |
| TC-COB-09 | A change on day D after COB(D) makes a reminder due on D | Intraday sweeper | Reminder sent once on D |

## 8. Reschedule primitives

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-RSC-01 | s3 due 03-09, s4 due 04-09 | moveDate(s3, 10) / (s3, 15) / move past s4 | OK 03-19 / rejected `>14` / rejected `order` |
| TC-RSC-02 | s1, s2 paid | deferToEnd(s3) | 8 instalments + 1: s3's $150 moves to month 9; v1 unchanged |
| TC-RSC-03 | s3 paid $50 of $150 | deferToEnd(s3) | s3 = $50 (paid); new last instalment $100 |
| TC-RSC-04 | Remaining $900 over 6 unpaid | reamortize(k = 6) | 12 × $75 |
| TC-RSC-05 | Remaining $100 over 2 unpaid | reamortize(k = 6) | 4 × $25 (floor applies) |
| PROP-RSC-01 | Random loan state, any primitive | apply | Σ = P; paid amounts unchanged; previous version unchanged |

## 9. Repayment ingestion (integration, real Postgres)

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-ING-01 | Active loan | Sale webhook | One transaction: repayment row, balanced journal entry, allocation, aging, timers, outbox events |
| TC-ING-02 | Same, but `ledger.post` throws | Sale webhook | Nothing persisted; inbox row stays unprocessed for retry |
| TC-ING-03 | Processed event | Same event id again | No-op |
| TC-ING-04 | One payment | `INVOICING.INVOICE.PAID` and `PAYMENT.CAPTURE.COMPLETED` | One repayment |
| TC-ING-05 | Mandate not yet active | Sale arrives first | Parked; applied once after activation; alert if older than 15 min |
| TC-ING-06 | `PAYMENT.SALE.PENDING`, then `COMPLETED` | Both | No credit while pending; credited once on completion |
| TC-ING-07 | Gross $150, fee $4.85 | Sale | Dr cash $145.15, Dr fees $4.85, Cr receivable $150 |
| TC-ING-08 | Outstanding $150 | $200 capture | Cr receivable $150, Cr `member_overpayment` $50 |
| TC-ING-09 | REPAID loan | `PAYMENT.SALE.REVERSED` $150 | Dr receivable, Cr cash; loan ACTIVE; `RepaymentReversed` |
| TC-ING-10 | One loan | 50 distinct payments in parallel | Serialised; final state equals sequential; no deadlock |
| TC-ING-11 | 200 loans | 1 payment each, in parallel | All posted; p99 ≤ 5 s; no lock waits on program cash |
| TC-ING-12 | Capture in EUR | Webhook | Rejected, alert; no posting |
| TC-ING-13 | `received_at` = 2027-01-01T07:30Z | Ingest | `effective_date` = 2026-12-31 |

## 10. Autopay mandate and reconciler

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-MND-01 | Loan without a mandate | `POST /autopay` twice | One subscription; same approve URL both times |
| TC-MND-02 | APPROVAL_PENDING | Webhook ACTIVATED and `/autopay/confirm` at the same time | One ACTIVE; one `AutopayActivated` |
| TC-MND-03 | Next cycle falls in a paused month | desiredMandate | SUSPENDED; plan = [SUSPEND] |
| TC-MND-04 | Next is the final instalment, $151, observed quantity 150 | plan | [REVISE(151)] |
| TC-MND-05 | Outstanding 0 | plan | [CANCEL] |
| TC-MND-06 | Revise response has an approve link | reconcile | Desired unattainable; member gets the link; Payment Link fallback for this cycle |
| TC-MND-07 | Member cancels in PayPal | `BILLING.SUBSCRIPTION.CANCELLED` | Mode INVOICE; `AutopayCancelled(MEMBER)`; SMS; not a default |
| TC-MND-08 | `next_billing_time` 5 days off the due date | reconcile | `MandateDriftDetected`; Payment Link for this cycle |
| PROP-MND-01 | Random desired and observed state | plan, apply, plan | Second plan is empty (convergence) |

## 11. Failed autopay and retry

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-RTY-01 | Ana's income usually lands on the 11th; due the 9th | Failure on the 9th | `RepaymentFailed`; SMS; retry planned for the 12th (in DPD 2–4, best income day) |
| TC-RTY-02 | Retry planned | Member pays by link first | Attempt CANCELLED; no capture |
| TC-RTY-03 | Hardship case open | Retry date arrives | No capture |
| TC-RTY-04 | Retry fails | — | No second automatic retry; grace nudge with Payment Link |
| TC-RTY-05 | Failure reported at dpd 4 | plan retry | No retry (window empty) |

## 12. Invoice mode, one-off payments, refunds

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-INV-01 | INVOICE mode | COB at due − 7, run twice | One invoice (`invoice_number` and command key) |
| TC-INV-02 | Open invoice for s3 | `ScheduleChanged` moves s3 | Old invoice cancelled; new one issued under v2 |
| TC-INV-03 | $150 invoice | $100 partial payment | Allocated; s3 PARTIALLY_PAID |
| TC-PAY-01 | — | `POST /payments` with 15050 cents | 422 `amount-not-whole-dollars` |
| TC-PAY-02 | Outstanding $300 | amount $400 | 422 `amount-exceeds-outstanding` |
| TC-PAY-03 | Outstanding $300 | Payoff order captured | REPAID; mandate CANCEL command; `LoanRepaid` |
| TC-PAY-04 | Approved order | Capture call and capture webhook race | One repayment |
| TC-PAY-05 | Any loan | `POST /payments/preview` | Correct allocation; no rows written (row counts before = after) |
| TC-REF-01 | Overpayment $50 | Refund request | Refund command against the latest capture; Dr overpayment, Cr cash |
| TC-REF-02 | Overpayment $50 | Refund $60 | 422 |
| TC-REF-03 | REPAID loan | Late autopay sale $150 | Auto-refund within one business day; overpayment back to 0 |

## 13. Reminders (engagement)

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-REM-01 | s2 due Friday | COB on Tuesday, run twice | One T−3 SMS |
| TC-REM-02 | s2 already paid | REMIND fires | `SKIPPED_PAID` |
| TC-REM-03 | Member in New York; send computed for 21:00 local | queue | `send_after` = 08:00 local the next day |
| TC-REM-04 | Member replied STOP | Any reminder | No SMS; in-app only |
| TC-REM-05 | Locale `es` | T−3 | Spanish template; amounts formatted in en-US currency style |
| TC-REM-06 | s3 moved by reschedule | Old version's reminder date arrives | No old-version reminder; new version reminder on the new date |

## 14. API, security, contract, architecture

| ID | Given | When | Then |
| --- | --- | --- | --- |
| TC-API-01 | Schedule unchanged | GET with `If-None-Match` | 304 |
| TC-API-02 | Member A's token | GET member B's loan | 404 (not 403) |
| TC-API-03 | Idempotency key used | Same key, different body | 409 `idempotency-key-reused` |
| TC-API-04 | — | Internal job with no token / with a member token | 401 / 403 |
| TC-API-05 | Desk | SSRM filter on a column not in the allowlist | 400 |
| TC-API-06 | 1,000 seeded loans | SSRM group by bucket | Group counts and sums equal SQL ground truth |
| TC-API-07 | Agent scope | Any agent tool | No payer ids or PayPal ids (schema and regex check) |
| TC-API-08 | — | Zapier SMS with a bad HMAC / a replayed body | 401 / 409 |
| TC-API-09 | Version 2 exists | GET timeline | Ghost events and dependencies for moved instalments |
| TC-API-10 | Webhook with an invalid signature | POST to ingress | Stored as unverified; never acted on |
| TC-CON-01 | All endpoints | Postman collection run | Every response validates against OpenAPI |
| TC-CON-02 | All events | Scenario tests | Every outbox payload validates against `domain-events.json` |
| TC-CON-03 | Executor | Recorded PayPal requests | Bodies match the shapes captured in spike S6 |
| TC-ARC-01 | Codebase | ArchUnit | Only `payments` imports the PayPal client |
| TC-ARC-02 | Codebase | ArchUnit | `engines/schedule` imports no Spring, jOOQ, `java.net` or `java.sql` |
| TC-ARC-03 | Codebase | Modulith verify | No module uses another module's internal packages |
| TC-INVJ-01 | Allocation row corrupted by hand | Nightly invariant job | INV-2 violation reported; Slack alert |

## 15. Chaos (Postman Simulator)

C1 to C8 as defined in [05 §2](05-sponsor-integrations.md#2-postman). Pass criteria for all: integrity endpoint drift = 0, no duplicate journal entries, and no command with more than one successful PayPal side effect.

## 16. End to end (sandbox)

| ID | Scenario | Pass |
| --- | --- | --- |
| E2E-01 | **Ana:** $1,200 disbursed → enrol autopay (sandbox approval) → demo months 1 and 2 collected → month 3 paid by invoice → month 4 autopay fails → bucket 1–7, SMS, retry planned → pause approved (F5) → Bryntum shift → payoff → REPAID | Ledger balanced; reconciliation drift 0; 8 of 8 on plan; `LoanRepaid` |
| E2E-02 | Member cancels autopay in PayPal mid-loan | Falls back to invoices; no missed instalment |
| E2E-03 | Overpayment and refund round trip in sandbox | Refund visible at PayPal; ledger and PayPal agree |

## 17. Performance

| ID | Target |
| --- | --- |
| PERF-01 | JMH: `allocate` at n = 36, m = 100 < 1 ms; `generate`, `age`, `timers` < 0.1 ms |
| PERF-02 | COB with 100k active loans, all with a timer due: < 15 min (goal < 3 min); typical day < 2 min |
| PERF-03 | `GET /schedule` at 50 VUs: p95 < 100 ms, error rate 0 |
| PERF-04 | Ingress burst of 500 webhooks/s for 60 s: ack p99 < 300 ms; all posted within 5 s p99 |

## 18. Agent evals

| ID | Check |
| --- | --- |
| EVAL-01 | Coach "what if I pay $X extra" — every number matches `POST /payments/preview` |
| EVAL-02 | Coach "why is my last payment different" — cites remainder placement correctly |
| EVAL-03 | Guardian option previews match `POST /schedule/preview` exactly |

## 19. Traceability (requirement → tests)

| FR | Tests |
| --- | --- |
| FR-01 | TC-GEN-*, PROP-GEN-*, TC-DUE-*, PROP-DUE-01 |
| FR-02 | TC-DDS-*, PROP-DDS-01 |
| FR-03 | TC-MND-01, TC-MND-02, E2E-01 |
| FR-04 | TC-INV-*, TC-TMR-04, E2E-02 |
| FR-05 | TC-PAY-*, C5 |
| FR-06 | TC-ING-*, C1–C3, C7 |
| FR-07 | TC-ALC-*, PROP-ALC-* |
| FR-08 | TC-ING-08, TC-REF-*, C8 |
| FR-09 | TC-ING-09, TC-ALC-09, C6 |
| FR-10 | TC-AGE-*, PROP-AGE-* |
| FR-11 | TC-RTY-* |
| FR-12 | TC-REM-* |
| FR-13 | TC-MND-03 to 08, PROP-MND-01, C4 |
| FR-14 | C7, TC-CON-03, nightly reconcile in E2E-01 |
| FR-15 | TC-PAY-03, TC-REF-03 |
| FR-16 | TC-API-01, TC-API-09, TC-CON-01 |
| FR-17 | TC-API-05, TC-API-06 |
| FR-18 | TC-COB-*, TC-TMR-* |
| FR-19 | TC-RSC-*, PROP-RSC-01 |
| FR-20 | TC-API-07, EVAL-* |
| FR-21 | TC-ING-01 (audit fields asserted), TC-INVJ-01 |
