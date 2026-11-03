# ADR 0026: Income Is Read Before the Submission Transaction, and the Fair-Check Consent Joins the Member's List

## Status
Proposed. Builds on [ADR 0012](0012-identity-members-staff-and-consent-ledger.md) (identity records, underwriting enforces), [ADR 0013](0013-paypal-payouts-billing-catalog-transaction-search-and-venmo.md) (Transaction Search in `payments`) and [ADR 0018](0018-consented-income-from-transaction-search-and-member-home-summary.md) (consented income, the 24 hour snapshot, failure refuses). Changes one thing ADR 0018 left open: where in the submission the PayPal read runs.

## Context
Two items were pending.

1. **A PayPal outage held a database transaction open.** With `seed.underwriting.income.source=TRANSACTION_SEARCH`, `MicroloanApplicationService.submitApplication` ran inside one `@Transactional` (30 s timeout). The PayPal read sat inside it, in `assessForLoanApplication` after the application row was saved. A slow or dead PayPal kept a connection and a row lock for up to the read deadline, and a failure rolled everything back as a `503`. Correct, but wasteful and fragile under load.
2. **The member's consent list did not show the fair check.** The fair check keeps its own consent in underwriting (`underwriting.income_consent`, the single record that gates every read). The list the member sees (`GET /api/v1/members/me/consents`, identity) knew only `INCOME_READ`, `TERMS`, `PRIVACY` and `RECORD_SHARING`, so a member could agree to the fair check without it ever appearing on their list, and could not take it off the list.

## Decision

### 1. Read PayPal first, then open the transaction
`submitApplication` is no longer annotated. It does, in order:

1. `UnderwritingFacade.prepareIncomeForApplication(memberId)`, **outside any transaction**. With the seeded source it does nothing. With `TRANSACTION_SEARCH` it reads PayPal only if the stored weeks are stale (older than `max-age`, default 24 h) and writes nothing during the read (`IncomeRefresher.fetchIfStale`). If the read fails the call throws the same `503 income-source-unavailable` as before, and **nothing has been written**: no application, no consent, no weeks. If it succeeds, one short transaction records the consent the member gave in this request and replaces the weeks together (`IncomeSummaryService`, `IncomeRefresher.store`).
2. A `TransactionTemplate` (the transaction manager is injected by setter, so the constructors other code uses are unchanged) runs the old body: eligibility, sizing, saving the application, the fair check and the event. The fair check now runs on the **stored** snapshot: `assessForLoanApplication` calls `IncomeRefresher.requireFresh` instead of reading PayPal, and `FairLoanCheckService.runFairLoanCheckOnStoredIncome` instead of `runFairLoanCheck`. If the snapshot went stale between the two steps it refuses with the same retryable `503` rather than call PayPal or decide on old numbers.

Consequences for the outage cases:

| Situation | Result |
| --- | --- |
| Stored income within `max-age`, PayPal down | PayPal is not called; the submission succeeds on the stored income |
| Stored income older than `max-age`, PayPal down | `503 income-source-unavailable` before any transaction or write (ADR 0018 stands: never decide on stale income) |
| No stored income, PayPal down | Same `503`; no consent, weeks or application left behind |
| Same request repeated | Fresh weeks mean no second read; the consent line on the list is recorded once |
| `SEEDED` source | Unchanged: no PayPal code runs at all |

Eligibility now sees the income that was just read. Before, eligibility ran on the weeks from the last read and the fair check on the new ones, so a first submission was sized with no income at all.

**Consent given in the request.** The member ticks the fair check in the request, so that is the consent. Because the read has to happen before the transaction, the consent and the weeks are recorded (together) before the application is checked. If the application is then refused as ineligible, the consent stays; the member gave it and can withdraw it from their list. The read itself happens before the consent row exists, which is why `fetchIfStale(memberId, consentGivenNow)` takes the flag and why a failed read leaves no consent.

**Preview.** `previewApplication` calls `UnderwritingFacade.refreshIncomeIfConsented` before its read-only transaction. It never records a consent. If PayPal cannot be read the preview uses the stored income: it is an estimate, and the submission is the call that refuses.

**Why not keep one transaction with a shorter timeout.** A timeout bounds the damage but still holds the connection for the whole read, and a rolled-back attempt re-reads PayPal on the retry. Splitting the work makes the slow part hold nothing and the retry free when the read succeeded.

### 2. The fair-check consent is a line on the member's list
- `ConsentType.FAIR_CHECK` (identity `api.dto`), current text version `seed.identity.consent.fair-check-version` (env `SEED_CONSENT_FAIR_CHECK_VERSION`, default `2026-11`), in `ConsentCatalog`. Migration `identity/V3__fair_check_consent.sql` widens the `consent_type_valid` check; the ledger rows and the guard trigger are untouched. The OpenAPI `ConsentType` enum and the two consent event enums in `contracts/events/domain-events.json` gain the value.
- **Recording.** `FairLoanCheckService.grantConsent` (used by `POST /underwriting/consent`, the facade and the loan application) saves underwriting's consent and then calls the `MemberConsentLedger` port, whose adapter `IdentityConsentLedger` calls `IdentityFacade.grantConsent(memberId, FAIR_CHECK, current version)`. Both happen in one transaction. Identity is idempotent on member, type and version while active, so a replay changes nothing. A member with no identity record (a demo id) is skipped by looking the member up first; a `404` raised inside the caller's transaction would mark it rollback-only. Only `FAIR_CHECK` is recorded: `INCOME_READ` stays the member's own explicit choice, as in ADR 0012.
- **Following the list.** `UnderwritingEventListener` now also handles `ConsentRevoked` of type `FAIR_CHECK`, with the same effect as `INCOME_READ` (ADR 0018): close the enforcing consent and forget the weeks read from PayPal. Granting `FAIR_CHECK` in the list alone grants nothing in underwriting.
- **One consent, two lines.** `underwriting.income_consent` is a single record behind both lines, so ending it ends both. Withdrawing either line from the list closes the other (`IdentityConsentLedger.withdrawn`), and withdrawing in underwriting (`DELETE /underwriting/consent`) closes both (`closed`). Today's rule that revoking income consent revokes the fair check as well therefore holds and is now visible on the list. The closings do not chase each other: identity publishes `ConsentRevoked` only when it actually closes an open line.
- **Old events are ignored.** Listeners run after commit, possibly after the member agreed again. `withdrawn` returns false, and nothing is closed or purged, when the withdrawn line is active again. This also fixes the same race for `INCOME_READ` that ADR 0018 had.

## Settings
| Key | Default | Meaning |
| --- | --- | --- |
| `seed.identity.consent.fair-check-version` | `2026-11` | Current text version of the fair-check consent; another version is refused with `consent-version-stale` |

No other setting changed. `seed.underwriting.income.max-age` (24 h) now also bounds how old the stored snapshot a submission may rest on can be.

## API
No new route. `GET/POST /api/v1/members/me/consents` and `DELETE /api/v1/members/me/consents/{type}` accept and list `FAIR_CHECK`; `POST /api/v1/loans/applications` behaves as before except that PayPal is read before its transaction. `UnderwritingFacade` gains `prepareIncomeForApplication` and `refreshIncomeIfConsented` (default no-ops). `IdentityFacade` is unchanged.

## Errors
No new problem types. `income-source-unavailable` (503, retryable) and `paypal-account-not-linked` (422) are now raised before the submission transaction opens, not inside it.

## Alternatives considered
- **Fall back to a stale snapshot when PayPal is down.** Rejected again (ADR 0018): the point of the real source is that a decision rests on current evidence. The 24 h window is the tolerance.
- **Make the read a background refresh and submit on whatever is stored.** Would let a first-time member submit with no income at all. A retryable refusal is more honest.
- **Make the controller call underwriting before the service.** Splits one use case across two layers and leaves other callers of the service unprotected. The service owns the order.
- **A second consent record in underwriting for the fair check.** The two are the same consent today; a second record would let them disagree. One record, two honest lines on the list.
- **Close only the line that was withdrawn.** Leaves a list that shows an active consent nothing honours.

## Consequences
- A PayPal outage holds no database connection during submission, and a failed read leaves no application, consent or income behind.
- A member sees the fair check on their list, can take it off, and the fair check stops at once (after the event is delivered), with the cached PayPal income forgotten.
- A submission can be refused (retryable) if the stored income goes stale in the moment between the read and the transaction. The window is milliseconds and a retry succeeds.
- Raising `seed.identity.consent.fair-check-version` asks members to agree again on their list; underwriting's own consent is not re-asked, as with the other types.
- The `lending` service reaches `PlatformTransactionManager` through a package-private setter; its public constructors are unchanged.
