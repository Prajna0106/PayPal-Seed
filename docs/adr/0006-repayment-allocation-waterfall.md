# ADR 0006: Repayment Allocation Waterfall and Overpayment Handling

## Status
Proposed (F4)

## Context
plan.md §7 says both "overpayments reduce the last instalments" and, for a double payment, "the extra goes to the next instalment". These conflict. Applying excess to the next instalment has a side effect with autopay: the next charge finds nothing due, collects anyway, and the excess rolls forward again every month after.

## Decision
Every money event is applied in two phases, then any remainder is held:
1. **Due money:** instalments due on or before `effectiveDate + 7 days`, oldest first. The 7-day window covers PayPal charging a day early and members paying this month's instalment a little ahead.
2. **Prepayment:** any excess pays instalments from the **last** one backwards (inverse order of maturity). The loan finishes earlier; every future autopay amount stays the same.
3. **Overpayment:** money beyond the outstanding balance is credited to a `member_overpayment` liability. Receivables never go negative. The member can ask for a refund at any time. Money received after the loan is repaid is refunded automatically within one business day.

All amounts are whole dollars (loan principal, instalments, accepted payments), so the waterfall never creates cents that the `$1 × quantity` autopay plan cannot charge.

## Consequences
- The plan.md conflict is resolved in favour of "reduce the last instalments".
- Double payments shorten the loan instead of being refunded by default. The refund stays available on request.
- The ledger needs the `member_overpayment` liability account (and `paypal_fees` for gross-to-net). These are additions to the F7a chart of accounts.
