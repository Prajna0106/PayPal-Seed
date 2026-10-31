# F4 — Implementation Plan

**Today:** Sat 31 Oct 2026 · **Feature freeze:** 9 Nov · **Deadline:** 12 Nov 12:00 PT. F4 sits in plan phase 4 ("Loans: F2, F4"), after the core engines.

## 1. Prerequisites from other features

F4 cannot post money without them. Where one is late, the stub keeps F4 moving.

| Needs | From | Minimum slice | Stub if late |
| --- | --- | --- | --- |
| `ledger.api.post(entry)` with `source_key UNIQUE`, balanced-entry trigger, accounts | F7a | Accounts and post, plus the balance query | None. This is on the critical path; build it first |
| `LoanDisbursed` event, `lending.loan` row with principal, term, due day | F2 | Loan creation and the disbursement event | A test fixture that inserts a disbursed loan |
| Webhook ingress, signature check, `webhook_inbox`, executor base with `payments.command` | payments (shared with F1) | Verify, inbox, normaliser skeleton | None; shared with F1 savings |
| Outbox and event publication | platform/messaging | Spring Modulith event publication registry | — |
| Income timing profile | F3 | `incomeTimingProfile(memberId)` summary | Stated payday from the application |
| Member session, staff roles | identity / security | Member principal; officer role | Dev-profile header auth |

## 2. Tasks (in build order)

Estimates are engineer-days. Each task ends with its tests green and a commit.

| # | Task | Done when | Est. | Tier |
| --- | --- | --- | --- | --- |
| T0 | PayPal sandbox spikes S1–S9 ([04 §10](04-paypal-integration.md#10-sandbox-spikes-day-1)) | Answers and recorded webhook fixtures committed; `remainderPlacement` decided | 0.5 | MUST |
| T1 | Contracts: OpenAPI paths and schemas (02 §6), event schemas (02 §7), agent tools; generate the Postman collection; start the mock server | `postman collection lint` passes; mock returns the schedule example; front end can build the timeline against it | 0.5 | MUST |
| T2 | Kernel: `Money` as long cents (USD); `BusinessClock.today()` as `LocalDate` in LA; `RepaymentPolicy` in `engines/rules` | Unit tests; existing modules compile | 0.25 | MUST |
| T3 | `engines/schedule`: generate, due dates, allocate, age, `suggestDueDay`, `retryDate`, `desiredMandate` + `plan`, reschedule primitives, `timers` | All PROP-* and engine TC-* green; JMH within budget (PERF-01); ArchUnit TC-ARC-02 | 1.5 | MUST |
| T4 | Flyway migrations (02 §4) for `lending`, `payments`, `engagement`; INV-1 deferred trigger; `REVOKE UPDATE, DELETE` on immutable tables | Migrations apply on Testcontainers; trigger rejects an unbalanced version | 0.5 | MUST |
| T5 | Ledger posting rules for F4: repayment (gross, fee, net, excess), reversal, overpayment refund; new accounts `paypal_fees`, `member_overpayment` | TC-ING-07, TC-ING-08, TC-ING-09 ledger lines | 0.5 | MUST |
| T6 | `RecordRepayment` and `RecordAdjustment` use cases: lock, insert, post, fold, age, rebuild timers, outbox, all in one transaction | TC-ING-01 to TC-ING-13 | 1.0 | MUST |
| T7 | payments: plan bootstrap script; executor commands with `PayPal-Request-Id` and retry policy; webhook normaliser (04 §7) with parked events; mandate create/confirm; reconciler | TC-MND-*, PROP-MND-01; chaos C1–C5 against WireMock | 1.5 | MUST |
| T8 | COB: start, shard, finish endpoints; **`scan` mode first** (the oracle), then the timers table and `timers` mode; intraday sweeper; demo-clock advance; `workflows/daily-close` on Render | TC-COB-01 to TC-COB-09 including the differential test TC-COB-07; run tree visible in Render | 1.0 | MUST (`timers` mode SHOULD) |
| T9 | Failed autopay: attempt tracking, single income-aware retry, watchdog at due + 1, nightly repayment reconcile | TC-RTY-*; chaos C7 | 0.5 | SHOULD |
| T10 | One-off payments: order create and capture, Payment Links, payoff quote, closure, overpayment refund, auto-refund after closure | TC-PAY-*, TC-REF-*; chaos C8 | 0.75 | MUST (orders and payoff); SHOULD (refunds) |
| T11 | Invoice mode: just-in-time invoices, cancel and reissue on reschedule | TC-INV-*, TC-TMR-04 | 0.5 | STRETCH |
| T12 | engagement: reminder queue, dedupe, quiet hours, en/es templates, Zapier outbound hook, inbound SMS endpoint | TC-REM-*, TC-API-08 | 0.5 | SHOULD |
| T13 | Read APIs: schedule (ETag), versions with diff, Bryntum timeline, repayments, both preview endpoints, SSE | TC-API-01, TC-API-02, TC-API-09, TC-PAY-05 | 0.75 | MUST |
| T14 | desk-query: SSRM endpoint with column allowlist, KPIs (PAR30, first-attempt success, cure rate), roll-rate matrix | TC-API-05, TC-API-06 | 0.75 | SHOULD (roll rates STRETCH) |
| T15 | Postman: request tests, webhook-replay folder, Simulator chaos C1–C8, monitors, CI job | All collection runs green in the preview environment | 0.5 | MUST (C1–C3), SHOULD (rest) |
| T16 | Elastic: event indexing, two ES\|QL rules, APM traces | One trace from webhook to journal visible; rules return the seeded case | 0.25 | SHOULD |
| T17 | E2E-01 (Ana) in sandbox; PERF-02 to PERF-04 | E2E-01 passes twice in a row; performance targets met | 0.5 | MUST (E2E-01) |

**Total:** about 11 engineer-days. With two engineers it fits between 2 Nov and 8 Nov.

## 3. Critical path and parallel tracks

```text
Engineer A (core):  T2 → T3 ─────────→ T4 → T6 → T8 → T17
Engineer B (rails): T0 → T1 → T5* → T7 ────────→ T10 → T9 → T15
Either / later:     T13 (after T6) · T12 (after T6 events) · T14 · T16 · T11
* T5 depends on F7a ledger core being merged
```

| Date | Milestone |
| --- | --- |
| Mon 2 Nov | Spikes answered; contracts and mock server up; engine generate and allocate green |
| Wed 4 Nov | Repayment ingestion end to end with a stub webhook; ledger balanced |
| Fri 6 Nov | Autopay mandate live in sandbox; COB in scan mode; first real sandbox autopay posted |
| Sun 8 Nov | Failure → bucket → reminder → retry; timeline and preview endpoints; chaos C1–C8 green |
| Mon 9 Nov | **Freeze.** E2E-01 passes; only fixes after this |

## 4. Cut lines

- **Solo or late:** COB stays in `scan` mode (it is correct, just O(loans)); autopay and orders only (no invoice mode); reminders are T−3 and payment-failed only; no roll rates; no APIMatic servicing SDK.
- **Never cut:** one-transaction posting (T6), dedupe by transaction id, the reconciler's CANCEL on payoff, chaos C1–C3, E2E-01. These carry the "no one is paid twice, the ledger always matches PayPal" promise.

## 5. Plan.md "done means" for F4, mapped

| plan.md says | Proven by |
| --- | --- |
| Eight instalments are generated | TC-GEN-01, E2E-01 |
| Two demo-month autopays and one manual invoice payment post to the ledger | E2E-01 (invoice step needs T11; if T11 is cut, use a checkout payment and say so) |
| A failed payment moves the loan to the 1–7 days bucket | TC-AGE-02, TC-RTY-01, E2E-01 |
| …and triggers the reminder and the safety net | TC-REM-*, `RepaymentFailed` consumed by hardship (F5) |

## 6. Risks

| Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- |
| Raising subscription quantity needs payer consent (S3) | Medium | Medium | `remainderPlacement = FRONT`; quantities only fall |
| Suspend and activate bills the skipped cycle (S4) | Medium | High | Keep the subscription active; collect paused-adjacent cycles by Payment Link; or Vault MIT (S9) |
| Sandbox cannot force subscription failures (S8) | Medium | Medium | Replay recorded, re-signed failure webhooks through Postman |
| F7a ledger slips | Medium | High | Ledger core is the first task of phase 2; F4 T3 and T4 do not depend on it |
| Timers mode has a subtle bug | Low | High | Scan mode ships first; differential test TC-COB-07; `seed.cob.mode` switch |
| Demo clock vs real PayPal time | High | Medium | Demo collection channel (04 §11), labelled on screen |
