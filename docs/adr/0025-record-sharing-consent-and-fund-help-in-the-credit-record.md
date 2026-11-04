# ADR 0025: Record Sharing Consent, and Help from the Safety Fund in the Credit Record

## Status
Proposed. Builds on [ADR 0012](0012-identity-members-staff-and-consent-ledger.md) (the consent ledger), [ADR 0014](0014-credit-record-projection-levels-and-signed-share-links.md) (the record, levels and share links), [ADR 0020](0020-member-reads-verified-actors-and-reserve-cover-on-the-schedule.md) (a reserve cover reaches the schedule as a repayment from `RESERVE`) and [ADR 0021](0021-wiring-desk-reserve-fraud-record-and-withdrawal-messages.md) (the wiring module feeds the record). Closes two items those ADRs left open.

## Context
1. **A share link did not need consent.** Identity has a `RECORD_SHARING` consent, but nothing checked it when `POST /api/v1/members/me/record/shares` made a link, and withdrawing it left every link working.
2. **A loan the fund paid was counted like any other.** ADR 0020 says a loan the reserve pays in full becomes `REPAID` and "the credit record counts it like any repaid loan. Whether a loan the fund paid should lift the member's record is a policy question, not decided here." Two rules pull against each other: hardship help must never count against a member, and it must not inflate their record either.

## Decision

### 1. Consent to share
- **Creating a link needs an active `RECORD_SHARING` consent at the current text.** Without it the answer is `409` with the stable problem `record-sharing-consent-required` (`RecordErrors.SHARING_CONSENT_REQUIRED`). A member identity does not know, an agreement to an older text, and any other consent type all count as "no consent".
- **The check runs under the member lock and before the idempotent replay.** A repeated `Idempotency-Key` does not hand a token out again after the member said no, and a member with neither consent nor a record is told about the consent, which they can act on.
- **Withdrawing the consent revokes every active link of that member.** The record has a new facade method `revokeAllShares(memberId)` (idempotent, takes the same member lock as creation, closes only links that are unrevoked and unexpired, so `EXPIRED` stays `EXPIRED` and a first revocation time stands). `app/RecordShareRevocation` calls it on identity's `ConsentRevoked` for `RECORD_SHARING`. Delivery is at least once and a repeat finds nothing active.
- **The public verify call is unchanged.** A link revoked this way fails the existing revocation check and returns the same generic `404 record-not-found` as any unknown, expired or tampered token. Nothing in the answer says consent was withdrawn.
- **No module dependency.** The record still imports no other business module (ADR 0014). It owns a port, `RecordSharingConsent`; `app/RecordSharingConsentAdapter` answers it from `IdentityFacade.consents(...)`. Identity's facade has no "current version" accessor and this slice does not edit identity, so the adapter reads the same property identity reads (`seed.identity.consent.record-sharing-version`, default `2026-11`) and compares it to the active line's version. If identity ever changes how it keeps the current text, this adapter follows it.
- **Window.** Between identity committing the withdrawal and the listener running (normally milliseconds), a link still verifies. New links are refused from the moment identity commits, because the check reads the ledger directly.

### 2. Help from the fund
**Hardship help never counts against a member, and it does not inflate their record.** Concretely:

| What the fund paid | Instalment counts | Loan counts |
| --- | --- | --- |
| Part or all of one instalment | Neither on time nor late for the member. A miss recorded on it stops being open. | See below |
| Every instalment of the loan | None | `COMPLETED_WITH_FUND_HELP`: repaid, no default, no miss, but not in `repaidOnPlan` or `repaidOffPlan`, so it does not count toward level promotion |
| Some instalments, the member the rest | The member's own count as before | The member's loan: on plan if no miss is the member's, off plan otherwise. A miss on an instalment the fund covered is not the member's |

- **A new fact, `INSTALMENT_FUND_COVERED`** (loan, instalment), keyed `inst-fund:{loan}:{seq}` and stored append-only like every fact (migration `V3`). The fold keeps what it needs in `RecordState.settled` as small markers (`fund:`, `#late`, `#missed`, `repaid:`, `class:`, `lift:`; all shapes in `SettledKeys`), so `member_record` needs no new column.
- **Order does not matter.** Events are not ordered, and the fund's repayment and the loan's repaid event are published in one lending transaction and handled concurrently. Each of the instalment, repaid and fund facts therefore re-evaluates how the loan is counted: a member's payment counted before the fund's part arrives is taken back; a loan first counted on plan and then found to be the fund's is moved to `COMPLETED_WITH_FUND_HELP`. The projector tests fold the same facts in several orders and expect the same counts.
- **A rise that turns out to rest on the fund's loan is taken back.** If the repaid loan lifted the level before the fund's facts arrived, the level returns to the higher of what it was before that loan and what the corrected counts support (`Cause.CORRECTION`, published on `RecordLevelChanged`). A correction never lifts a level and never goes below what the member's own facts support. A default clears the markers. This is the only case where a level falls other than at a default; it undoes a race, not a member's behaviour.
- **Only counts are exposed.** `LoanCounts.completedWithFundHelp` on the record view, `loansCompletedWithFundHelp` on the verified answer (`SUMMARY` and `FULL`, frozen in the snapshot as `loans_fund_help`), and the new history kind. Nothing says why help was needed; hardship signals and cases never reach the record (ADR 0011). Contract updated in `contracts/openapi/seed-api.yaml`.
- **Feeding it.** `RecordFeed` hears lending's `RepaymentReceived` with source `RESERVE` (ADR 0020) and records `INSTALMENT_FUND_COVERED` for the instalments the schedule shows as completed on the day the fund paid, before it records the paid instalments. A miss lending attributes to our own late decision (`SLA_BREACH`) is still never forwarded (ADR 0021).

**Why this and not the alternatives.**
- *Count a fund-paid loan like any repaid loan* (ADR 0020's default): the member's level and the partner's answer would show repayment the member did not make. Rejected.
- *Treat fund help as a miss*: punishes the people the safety net exists for, against ADR 0011. Rejected.
- *Count a loan the fund only partly paid as the fund's too*: the member paid something and a single hardship event would then keep them from ever climbing. The rule is deliberately literal: the fund's loan is one where every instalment was the fund's. The instalments it covered still do not count as the member's own on-time repayments.

## Consequences
- **Needed from lending.** An instalment line does not say who paid it, and `LoanRepaid` does not say whether the fund did. The feed estimates it from the schedule (instalments completed on the day of the `RESERVE` repayment). That misses an instalment the fund only part-paid and the member finished later, and would take an instalment the member completed the same day for the fund's. The exact field is `InstalmentLine.reserveCoveredCents` (cents of that instalment paid by source `RESERVE`), and ideally `LoanRepaid.reserveCoveredCents`. With it the feed records `INSTALMENT_FUND_COVERED` from every handler, the ordering correction above is no longer needed in practice, and the estimate in `RecordFeed.fundCoveredSeqs` is deleted.
- Existing share tests changed: creating a share now needs a registered member with the consent; the verified answer, the snapshot columns and `LoanCounts` gained a field. Behaviour for members who consent is unchanged.
- The member web app must offer the `RECORD_SHARING` consent before the share action and map `record-sharing-consent-required` to a plain message (web rule 6). Not part of this slice.
- A tiny window remains where a link verifies after consent is withdrawn (above). Closing it would put an identity read on the public verify path; not worth it for a count-and-level answer cached for five seconds.
- The correction path means a level can change twice for one loan (up, then back) in the rare case the fund's facts arrive last. Both changes are published and dated.

## Follow-up: exact fund amounts and voided loans
Lending delivered what the "Needed from lending" item asked for ([ADR 0024](0024-voiding-a-returned-disbursement-and-returning-unused-reserve-cover.md) adds the void). Two changes, in `RecordFeed` and `credit-record`; no new module dependency.

### 1. The estimate is gone: the fund's cents decide
`InstalmentLine.reserveCoveredCents` says how much of an instalment the fund paid, so `RecordFeed.fundCoveredSeqs` (instalments completed on the day of a `RESERVE` repayment) is deleted. Every handler that reads the schedule now builds the facts from `RecordFeed.instalmentFacts`:

| Instalment, fully paid | Facts | Counts as |
| --- | --- | --- |
| The fund paid all of it (`reserveCoveredCents >= amountCents`) | `INSTALMENT_FUND_COVERED`, then the paid fact | Neither on time nor late for the member; a miss on it is not theirs |
| The fund paid part, the member finished it | The paid fact only, dated the day the member finished | The member's own instalment, on time or late by the member's own payment date. A miss on it stays the member's |
| The fund paid nothing | The paid fact only | The member's own, as before |

An instalment the fund part-paid and the member has not finished is still open (nothing is reported for it, and a miss on it is still the member's to settle). This narrows the table in decision 2 above, which said "part or all of one instalment": help counts as the fund's only for the whole instalment, because a member who completes the rest has made a real payment and should not lose credit for it. Two cases the estimate got wrong are now right: the fund part-paid and the member finished later (the member's), and the member completed an instalment on the day the fund paid another (the member's). `LoanRepaid.reserveCoveredCents` is not needed by the record: the lines carry the same information per instalment. The out-of-order correction (`retract`) stays, because events are still delivered in any order.

### 2. A voided loan leaves the record
A loan voided because its disbursement payout came back (`LoanVoided`, ADR 0024) must not show as a loan, an instalment, an on-plan or off-plan count, a default, or a level.
- **New fact `LOAN_VOIDED`** (loan), keyed `loan-void:{loan}` (migration `V4`), fed by `RecordFeed` from `LoanVoided`. Facts stay append-only; nothing is deleted.
- **Rebuild, not subtraction.** Ingesting a void rebuilds the member's state from all their stored facts in order, skipping every loan-scoped fact (disbursed, paid, missed, fund-covered, repaid, defaulted, hardship plan applied) of every voided loan (`RecordProjector.replay`). The result is what the record would be if the loan never existed, so it also covers a default or a promotion the loan caused, which a subtraction could not undo. The state keeps a `void:{loan}` marker.
- **Level.** The rebuilt level replaces the old one. If it differs, `RecordLevelChanged` is published with cause `CORRECTION`, as in the fund path, up or down: down when the loan lifted the level, up when it was the only reason for a default. Nothing else lowers a level.
- **Order does not matter.** A fact of a voided loan that arrives after the void is stored and ignored by the fold (the marker); one that arrived before is removed by the rebuild. Both orders give the same state (projector tests). Delivery twice is a `DUPLICATE` on the fact key.
- **History.** The member's and the desk's history hide a voided loan's facts and the void itself; the facts remain in the table. The contract enum `FactKind` is unchanged, since `LOAN_VOIDED` never appears in a response.

Consequences: `LoanVoidIT.whatTheMemberPaidBeforeTheReturn...` asserts the record's stored fact count does not grow after the return; one `LOAN_VOIDED` fact now appends, so that assertion should exclude it (lending's test, not changed here). A record whose only fact was a voided loan keeps a row with level `NEW` and `since` set to the void date.
