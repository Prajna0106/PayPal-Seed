# ADR 0004: Immutable Schedule Versions, Derived Instalment Status

## Status
Proposed (F4)

## Context
A loan's repayment plan changes during its life: hardship options move a date, pause a month or spread the balance over more months (F5). Payments arrive late, early, partly, twice or out of order, and can be reversed. Storing a mutable `status` and `paid_amount` on each instalment row means every reschedule and every reversal has to migrate those columns correctly. That is where loan systems usually corrupt data.

## Decision
- A schedule is a sequence of **immutable versions** (`lending.schedule_version`, `lending.instalment`). A change creates version N+1. Old versions are never updated or deleted (enforced by `REVOKE UPDATE, DELETE`).
- Each version must sum to the principal (deferred constraint trigger).
- **Paid amounts and instalment status are derived**, not stored as decisions. `allocate(currentVersion, allMoneyEventsInOrder)` is a pure fold in `engines/schedule`. Its output is written to a projection table (`instalment_allocation`) only as a cache, and is fully rebuilt on every change.
- Facts that must not change after the event (`InstalmentMissed`) are recorded separately as append-only rows.

## Consequences
- A reschedule needs no data migration: insert the new version and re-run the fold.
- A reversal, or an event that arrives late, is handled the same way, so the result cannot depend on arrival order.
- The full history of plans is available for the timeline (ghost events), the audit and the credit record.
- The fold costs O((n + m) · n) per change, which is microseconds for real loan sizes.
- The projection can be dropped and rebuilt at any time; the nightly invariant job compares it with the ledger.
