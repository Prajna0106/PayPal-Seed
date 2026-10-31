# ADR 0011: Hardship Case Lifecycle, Consent, Grace Hold and Standing

## Status
Proposed (F5)

## Context
A hardship case runs from detection to a changed schedule, across a member's reply, a human approval and the F4 reschedule. Four problems have to be solved together.

1. **Consent.** The member must choose. The system must never move a member's dates because a model, or an officer alone, thought it was a good idea.
2. **The approval gap.** F4 records an `InstalmentMissed` fact, append-only and never retracted, once grace runs out. A member who asks for help on day 3 of a 5-day grace and waits for an officer could be recorded as missed because approval was slow.
3. **Standing.** If asking for help harmed a member's record, members would stop asking, and the safety net would defeat its own purpose.
4. **Accountability.** Pauses and restructures change the member's time in debt and their PayPal subscription. A named human must own each decision, and a second human must check the bigger ones.

## Decision
**Lifecycle.** A case is a state machine with durable timers.
`DETECTED → OUTREACH_SENT → OFFER_VIEWED → OPTION_CHOSEN → PENDING_APPROVAL → APPROVED → APPLIED` is the main path. The terminal states are `DECLINED_BY_MEMBER`, `REJECTED_BY_OFFICER`, `EXPIRED`, `CANCELLED_PAID` and `SUPERSEDED`. `APPROVED_EXECUTION_FAILED` is retryable. A member who asks for help starts at `OFFER_VIEWED`. There is at most one open case per loan.

**Consent is a record.** A choice is stored with the option snapshot hash, the channel, a timestamp and the policy version. The snapshot is the preview the member saw. The officer approves the same snapshot, and the apply uses the same hash. If the schedule version has changed, the offer is rebuilt and the member reconfirms. "Do nothing" and `DECLINE` are always visible and never change anything.

**Approval tiers.**

| Option | Approval |
| --- | --- |
| A, move a date by 1 to 14 days | Member choice, then automatic approval by policy. The approval row records the actor `POLICY` and the policy version |
| B, one-month pause | Officer (maker) and a different officer (checker) |
| C, smaller and longer plan | Officer (maker) and a different officer (checker) |
| D, pay what you can | An ordinary payment, no approval |

The checker is not the maker and not the member. The SLA is 1 business day, with an escalation at 50% and a page at 80%.

**Grace hold.** When a member has chosen an option that needs approval and grace would expire within 3 days, hardship asks lending to place a hold on that instalment. The hold suppresses only the recording of `InstalmentMissed`. Days past due and the bucket are still computed from the dates, so the numbers stay true. A hold lasts at most `holdDays` (3), exists once per (loan, instalment, case), and is never extended. It ends when the option is applied, the officer rejects, the member declines, the case expires or the days run out. If the miss still stands, it is recorded on the first close of business after the hold ends, and the timeline shows the hold.

**Where the hold lives: in `lending`.** Hardship calls `lending.api.placeHold(loanId, seq, until, caseRef)`. Lending stores it in `lending.loan_hold` and its own close of business reads its own table. Lending also keeps an event-fed "open case" flag, which satisfies F4's rule that no retry fires while a case is open.

**Standing.** An applied option is "on plan". The credit-record feed carries the case id and the marker. The risk level decides only whether to reach out. It is never an input to eligibility, pricing, limits or graduation, and an architecture test checks that `credit-record` and `underwriting` do not read hardship signals. Requesting help is never a negative fact.

## Alternatives considered

- **Hold stored in hardship, with lending calling hardship at close of business.** Rejected. Lending would depend on hardship while hardship depends on lending, a module cycle that Spring Modulith would reject. Event-fed state in lending keeps the dependency one way.
- **A longer grace period for everyone.** Rejected. It changes F4's grace policy and the bucket boundaries for all members, to fix a gap that affects only members who have already asked for help.
- **Retroactively delete or edit an `InstalmentMissed` fact.** Rejected. F4 INV-9 makes it append-only so history cannot be rewritten, and the credit record depends on that.
- **No hold; rely on the SLA alone.** Rejected. An SLA of one business day fits in a 5-day grace most of the time, but a weekend and a request on day 4 would still record a miss caused by the desk, not the member.
- **Implicit consent: apply the recommended option unless the member objects.** Rejected. It moves dates without a yes, and silence is not consent.
- **Automatic approval for pauses and restructures.** Rejected in [ADR 0009](0009-ai-proposes-code-computes-human-approves.md). These change the member's time in debt and need a person who is accountable.
- **Keep the case inside `lending`.** Rejected. Lending owns schedules and money, not outreach, consent or approvals. A separate module keeps those rules, the agent integration and governance out of the loan core.

## Consequences
- A slow approval can no longer create a missed-payment fact, and the hold is short, visible and bounded.
- Standing is protected by construction, not by policy text alone.
- A rejected or expired case may still end in a recorded miss after the hold. That is honest, and the member was no worse off for asking.
- Lending gains one table and one small port, so F4's close of business needs a small, tested change.
- The apply is idempotent on `approvalRef = caseId + optionId + decisionId`. A retry after a failure applies once.
- Snapshot hashes make approvals precise, at the price of occasional re-offers when the schedule changes between the choice and the decision.
- Every transition and decision is written to the hash-chained audit log, so a case can be replayed end to end.
