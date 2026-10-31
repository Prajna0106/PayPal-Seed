# ADR 0007: Business-Date Close of Business with Durable Timers

## Status
Proposed (F4)

## Context
Loans must be aged into late buckets, reminders sent, invoices created and retries fired on specific dates. The demo clock moves months in seconds, so the job must catch up over many dates without skipping any. plan.md targets 100,000 loans in under 15 minutes. Most loans have nothing happening on most days.

## Decision
- **Business date.** All loan logic uses a `LocalDate` business date in `America/Los_Angeles`, from `BusinessClock`. COB for date D runs once, only after D − 1 is complete, and catch-up runs every missing date in order. `lending.cob_run(business_date PRIMARY KEY)` enforces one run per date.
- **Durable timers.** A pure function `timers(loanState, asOf)` lists the future dates on which the loan's state can change (reminder, due, watchdog, grace nudge, grace expiry, bucket boundaries, invoice creation, retry). They are stored in `lending.loan_timer` and rebuilt in the same transaction as every change to the loan.
- **COB fires due timers.** Each shard selects loans with `fire_on ≤ D`, applies the effects, and rebuilds the timers in one transaction per loan. Fired timers are deleted in that same transaction, which gives an exactly-once effect even when a step is retried.
- **Scan mode.** A brute-force mode computes the same effects for every active loan without timers. It ships first, serves as the test oracle (a differential test over a simulated year), and remains a runtime switch (`seed.cob.mode`).
- **Orchestration.** Render Workflows fans the shards out with retries; each step is an idempotent backend job endpoint. The worker role's scheduler can run the same sequence if Render Workflows is unavailable.

## Consequences
- COB cost is proportional to the number of state changes, not the number of loans.
- Catch-up over many dates (demo clock) is cheap.
- One more table and one more pure function to keep correct. This is mitigated by the differential test and the scan-mode fallback.
