# Feature 4 — Repayment Schedule and Autopay

**Status:** Backend implemented and tested (see [Implementation status](#implementation-status)) · **Owner module:** `backend/modules/lending` (with `payments`, `ledger`, `engagement`, `desk-query`) · **Plan reference:** [plan.md §7](../../../plan.md) · **Date:** 2026-10-31

Every disbursed loan gets an immutable, versioned schedule of equal 0% instalments on the member's chosen day. PayPal collects the money (autopay subscription, a just-in-time invoice, or a one-off checkout or payment link). Our schedule engine and ledger stay the source of truth. A daily close-of-business (COB) ages every loan into late buckets. Reminders go out before money is due, never after it is too late to help.

## What makes this design strong

1. **The schedule is the truth; PayPal is a collection channel.** A desired-state reconciler drives the PayPal subscription toward what the schedule says (quantity, suspended or active, cancelled). It works like a Kubernetes controller, so lost webhooks and API timeouts self-heal ([ADR 0005](../../adr/0005-paypal-mandate-desired-state-reconciliation.md)).
2. **Instalment status is derived, never stored.** Paid amounts per instalment are a pure fold of repayments over the current schedule version. A hardship reschedule needs no data migration; the fold simply re-runs ([ADR 0004](../../adr/0004-immutable-schedule-versions-derived-status.md)).
3. **One ingestion funnel, deduplicated by money identity.** Autopay sales, invoice payments, checkout captures, reconciler finds, refunds and reversals all become one `RepaymentReceived` shape. Dedupe uses the PayPal transaction id, not the webhook event id, because one payment can arrive as two different webhook events.
4. **Whole-dollar instalments with the smallest possible maximum.** `ceil(P/n)` is the lowest largest-instalment any split can achieve. That is the number the affordability rule constrains. It also lets one `$1 × quantity` PayPal plan collect any instalment exactly.
5. **Close of business is O(changes), not O(loans).** Each loan's next state change (reminder day, due day, grace expiry, bucket boundary) is computed by a pure function and stored as a durable timer. COB fires only the timers that are due. A differential test proves this matches a brute-force daily scan.
6. **Income-aware due days and retries.** The due day and the single failed-payment retry land on the days the member's own consented PayPal income usually arrives.
7. **Facts are append-only.** Once a grace period expires, an `InstalmentMissed` fact is recorded. A later reschedule can change the plan but can never erase history. Asking for help *before* the miss keeps the record clean.

## Documents

| # | Document | What it answers |
| --- | --- | --- |
| 1 | [01-requirements.md](01-requirements.md) | Functional requirements, acceptance criteria, non-functional targets, invariants |
| 2 | [02-design.md](02-design.md) | Module ownership, data model (DDL), state machines, API, events, security, observability |
| 3 | [03-algorithms.md](03-algorithms.md) | Generation, due dates, allocation waterfall, aging, timers, COB, reconciler, retries, reschedule primitives |
| 4 | [04-paypal-integration.md](04-paypal-integration.md) | Subscriptions, invoices, orders, payment links, refunds, webhooks, idempotency, sandbox spikes |
| 5 | [05-sponsor-integrations.md](05-sponsor-integrations.md) | Render, Postman, Bryntum, AG Grid, Zapier, Elastic, Astropods, APIMatic: concrete jobs and demo moments |
| 6 | [06-test-plan.md](06-test-plan.md) | Test cases with IDs: property, unit, integration, contract, chaos, E2E, performance |
| 7 | [07-implementation-plan.md](07-implementation-plan.md) | Ordered tasks, done-when, estimates, dependencies, cut lines |

### Architecture decision records

- [ADR 0004 — Immutable schedule versions, derived instalment status](../../adr/0004-immutable-schedule-versions-derived-status.md)
- [ADR 0005 — PayPal mandate as a desired-state reconciled collection channel](../../adr/0005-paypal-mandate-desired-state-reconciliation.md)
- [ADR 0006 — Repayment allocation waterfall and overpayment handling](../../adr/0006-repayment-allocation-waterfall.md)
- [ADR 0007 — Business-date close of business with durable timers](../../adr/0007-business-date-cob-durable-timers.md)
- [ADR 0008 — Type-safe SQL with jOOQ, generated per module from the migrations](../../adr/0008-jooq-per-module-generated-sql.md)

## Where this design changes plan.md

These are deliberate corrections. Each is explained in the linked document.

| plan.md says | This design | Why |
| --- | --- | --- |
| "Any rounding goes on the last instalment" | Remainder dollars spread +$1 over the last `r` instalments; all instalments are whole dollars | Minimises the largest instalment, which the sizing rule caps; lets the `$1 × quantity` plan charge exactly ([03 §2](03-algorithms.md#2-generate-equal-whole-dollar-instalments)) |
| "Overpayments reduce the last instalments" and "extra goes to the next instalment" (conflicting) | Due amounts oldest first, then the excess goes to the last instalments (inverse maturity) | The next-instalment rule makes autopay over-collect every month that follows ([ADR 0006](../../adr/0006-repayment-allocation-waterfall.md)) |
| "Recurring invoice series created from the schedule" | One invoice per instalment, created 7 days before due | A recurring series cannot follow a rescheduled plan; just-in-time invoices always match the current version ([04 §4](04-paypal-integration.md#4-invoice-mode-just-in-time-invoices)) |
| Ledger chart has no fee or overpayment accounts | Adds `paypal_fees` (expense) and `member_overpayment` (liability) | Reconciliation against Transaction Search needs gross, fee and net; receivables must never go negative ([02 §5](02-design.md#5-ledger-postings-owned-by-ledger-requested-by-lending)) |
| Daily job scans every loan | Durable per-loan timers; brute-force scan kept as the fallback and test oracle | 100k loans in under 15 minutes with most days touching under 10% of loans ([ADR 0007](../../adr/0007-business-date-cob-durable-timers.md)) |

## Open spikes (must run before build, day 1)

Listed in [04 §10](04-paypal-integration.md#10-sandbox-spikes-day-1). The design has a fallback for each, so none blocks the build.

## Implementation status

**Built (backend, `backend/`):**

| Area | Where |
| --- | --- |
| Pure engine: generate, due dates, allocation fold, aging, timers, retry date, due-day suggestion, mandate reconciler, reschedules | `engines/schedule` |
| Double-entry ledger core (accounts, balanced append-only journal) | `modules/ledger` |
| Loans, schedule versions, money funnel, recalculation, close of business (TIMERS and SCAN modes), member, desk and job APIs | `modules/lending` |
| PayPal adapter, command log, webhook ingress and normaliser, inbox, mandate reconciliation, orders, retry capture, watchdog, refunds | `modules/payments` |
| Reminders (dedupe, quiet hours, opt-out, en/es), Zapier SMS out and in | `modules/engagement` |
| Roles, error model (RFC 9457), demo clock, outbox retry | `app`, `platform/*` |
| OpenAPI | `contracts/openapi/seed-api.yaml`, `internal-jobs.yaml` |

**Verified:** `./gradlew build` runs 116 tests:
- 77 engine unit and jqwik property tests
- 31 integration tests on a real PostgreSQL 16, including a day-by-day differential check of close of business against a pure oracle in both TIMERS and SCAN modes
- 4 engagement unit tests
- 4 architecture tests (Spring Modulith boundaries, engine purity, PayPal and ledger access)

The boot jar was also run against a PostgreSQL 17 cluster and driven over HTTP.

**Not built yet (next steps):**
- Invoice mode. Manual collection uses checkout orders.
- Desk server-side row model, KPIs and roll rates.
- Postman collection, Simulator and monitors.
- Render Workflows script.
- Elastic indexing.
- The F3 income profile (due-day and retry fall back to the stated payday and to mid-grace).
- Real PayPal sandbox spikes S1 to S9.
