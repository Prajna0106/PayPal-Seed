# ADR 0005: PayPal Mandate as a Desired-State Reconciled Collection Channel

## Status
Proposed (F4) · Revisit after sandbox spikes S3, S4 and S9

## Context
Autopay uses a PayPal subscription on a `$1 × quantity` plan (plan.md §12). The subscription has its own state (status, quantity, next billing time, outstanding balance). Our schedule changes for reasons PayPal does not know about (hardship, prepayment, payoff). Webhooks can be lost or reordered, and API calls can time out after PayPal has already acted. Driving the subscription with one-off imperative calls ("on pause, call suspend") breaks whenever any one of those calls or events goes missing.

## Decision
- The **schedule is the source of truth**. The PayPal subscription is an actuator.
- A pure function `desiredMandate(loan, observed, asOf)` states what the subscription should look like now: CANCELLED, SUSPENDED, or ACTIVE with a given quantity.
- A pure function `plan(desired, observed)` returns the fewest commands (CANCEL, SUSPEND, ACTIVATE, REVISE) to get there.
- The reconciler runs after every change to the loan and once a day for every live mandate. It always observes (GET) before it acts. `plan(desired, apply(plan))` must be empty (tested as a property).
- Overdue amounts are never added to the subscription quantity, and `auto_bill_outstanding = false`. Overdue money is collected by one retry or by a Payment Link.
- When the desired state cannot be reached (payer consent needed, billing date drift over 3 days), the reconciler raises `MandateDriftDetected` and that cycle falls back to a Payment Link.

## Alternatives considered
- **Imperative calls on events.** Simpler at first, but not self-healing.
- **PayPal Vault v3 with merchant-initiated orders.** Gives exact amounts on exact dates and avoids quantity and suspend semantics. It needs vault eligibility on the merchant account and shows less of the Subscriptions API. This is the fallback if S3 or S4 fail and the preferred demo collection channel if S9 passes. The reconciler interface stays the same, because the desired state is expressed in schedule terms.

## Consequences
- Lost webhooks, timeouts and manual changes made in PayPal all converge on the next reconcile.
- Every PayPal write is the result of a pure, testable decision.
- Some PayPal behaviour must be confirmed in spikes before the reconciler's tolerances are final.
