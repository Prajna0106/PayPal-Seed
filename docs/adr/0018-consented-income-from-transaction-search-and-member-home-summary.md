# ADR 0018: Consented Income from Transaction Search, and the Member Home Summary

## Status
Proposed. Builds on [ADR 0012](0012-identity-members-staff-and-consent-ledger.md) (identity records consent, underwriting enforces), [ADR 0013](0013-paypal-payouts-billing-catalog-transaction-search-and-venmo.md) (Transaction Search in `payments`), [ADR 0014](0014-credit-record-projection-levels-and-signed-share-links.md) (the wiring module composes modules that must not depend on each other) and [ADR 0016](0016-signed-actor-tokens.md).

## Context
Two things were open.

1. **Income reached `underwriting` only through a seeded internal endpoint.** `PUT /internal/underwriting/members/{memberId}/income` stores weekly totals for demos. Payments can now read PayPal Transaction Search ([ADR 0013](0013-paypal-payouts-billing-catalog-transaction-search-and-venmo.md)), but nothing used it. Two loose ends from ADR 0012 were also still open: withdrawing `INCOME_READ` in identity did not stop underwriting, and `desk-query` had no event telling it that a fair-check assessment changed ([ADR 0015](0015-desk-read-models-keyset-grids-and-replayable-projections.md)).
2. **The web Home page needed one call.** Savings, lending, payments, underwriting, the credit record and hardship each answer for their own slice. Calling six endpoints per page view makes the page as slow and as fragile as the slowest one.

## Decision

### 1. A real income source, behind a switch
`seed.underwriting.income.source` is `SEEDED` (default) or `TRANSACTION_SEARCH` (`transaction-search` is accepted too; anything else stops the start-up).

With `SEEDED` nothing changes: no code path reads PayPal, the request's own monthly figures are used, and the seeding endpoint keeps working.

With `TRANSACTION_SEARCH`, `IncomeRefresher` runs at the start of `POST /underwriting/assessments` and of `assessForLoanApplication` (the path the loan application and the desk use). It reads PayPal **only if all three hold**:

- the source is `TRANSACTION_SEARCH`;
- the member has an **active income consent in underwriting** (the only consent the fair check reads);
- the stored weeks are older than `seed.underwriting.income.max-age` (default `24h`; a read stamped in the future counts as stale; exactly at the limit is still fresh).

Without consent, `consent-required` is returned as before and PayPal is never called (proved by a test that counts the fake PayPal's calls).

**What is read.** `PaymentsFacade.incomeTransactions(account, today - 91 days, today - 1 day)` in one call; payments cuts it into the 31-day windows PayPal accepts and nets fees and refunds. `IncomeBuckets` (pure) sums `netCents` into the same 13 consecutive 7-day buckets the seeded path stores, so the fair-check maths is untouched. A credit outside the 91 days (today is incomplete, older is out of the window), a credit that nets to zero or less, and a transaction id seen twice add nothing. The 13 buckets are always written, empty ones too, so a refund that arrives later overwrites a week that used to show income.

**What is stored.** `IncomeRepository.replaceFetchedWeeks` deletes the member's weeks and inserts the 13 new ones in one transaction, then stamps `income_consent.income_refreshed_at` (staleness clock) and `income_anchor` (the business date the buckets were cut for). Both columns are new (`V4__income_refresh.sql`) and stay null for seeded weeks, which therefore always count as stale under `TRANSACTION_SEARCH` and are replaced.

**Alignment.** Buckets are cut for the day of the read. If the summary were then read a day later with its own window, every stored week would slide one bucket and the newest bucket would read as zero income, which the hardship scorecard would take for a drop. So for up to 7 days after a read the summary keeps the read's alignment (`IncomeSummaries.MAX_ANCHOR_LAG_DAYS`); past that it is cut for the day asked, and old data shows as the gap it is. Seeded weeks have no anchor and behave exactly as before.

**With the real source, the figures come from PayPal, not from the request.** When the real source is on and PayPal shows income, `runFairLoanCheck` replaces the `monthlyIncomes` of the request with the three monthly figures derived from the weeks (the same derivation `assessForLoanApplication` always used). A member cannot type a higher income than their PayPal shows. If PayPal shows no income at all, the request's figures (or the stated income) are used and the check says so (`usedFallbackStatedIncome`).

**Failure refuses; it never falls back.** `PaymentsIncomeSource` is the only code that calls payments. It runs the call on a virtual thread with a total deadline (`seed.underwriting.income.timeout`, default `30s`). Any failure (PayPal unavailable, refused, disabled in payments, a timeout, an unexpected exception) becomes one problem: `503 income-source-unavailable`, `details.retryable = true`, `details.reason` naming the cause code (`dependency-unavailable`, `paypal-rejected`, `timeout`...). No PayPal text and no stack trace leave the process. Nothing is stored and no assessment is created, so the stored weeks stay what they were and a decision is never computed from stale or partial data.

**Which account.** The `IncomeAccountResolver` port maps a member to the reference payments reads with. A member with no identity record gets `422 paypal-account-not-linked` ("Link your PayPal account first") and nothing is fetched. The adapter calls `IdentityFacade.member`. **Identity does not yet expose the payer id**, so the adapter passes a blank reference and payments reads the program account; that is the sandbox demo path and is only correct while `seed.paypal.transaction-search.on-behalf` stays off. Switching partner reading on needs `IdentityFacade` to return the payer id (see "Needed from other modules").

**Dependencies.** `underwriting` now depends on `identity` and `payments` (their `api` packages only). The graph stays acyclic: `identity` depends on nothing; `payments` depends on `savings`, and `savings` depends on `ledger`. Nothing depends back on `underwriting` through them.

### 2. Consent stays honest
- `UnderwritingEventListener` listens to identity's `ConsentRevoked`. For `INCOME_READ` it calls `IncomeConsentSync.incomeReadWithdrawn`, which closes underwriting's enforcing consent and forgets the weeks read from PayPal (`purgeFetched`: weeks deleted, read stamp and anchor cleared). It is idempotent on the member: a second delivery finds the consent closed and the weeks gone. A member who never gave underwriting a consent gets no row created by withdrawing one. Other consent types are ignored.
- Granting `INCOME_READ` in identity grants nothing in underwriting (ADR 0012 stands); the web app still calls both.
- Revoking through underwriting's own `DELETE /api/v1/underwriting/consent` is unchanged and does not delete the cached weeks; they cannot be read without consent and are replaced at the next refresh.

### 3. `AssessmentChanged` for the desk read model
`underwriting.api.events.AssessmentChanged(eventId, assessmentId, memberId, applicationId, recommended, recommendedAmountCents, officerDecision, memberDisputed, createdAt, changedAt)` is the shape drafted in `contracts/events/pending/desk-query.json`. It is a **whole-state snapshot**, published inside the transaction that saved the assessment, on creation, on a dispute and on an officer decision (approved, adjusted or declined). `changedAt` is the decision time once decided and the creation time before that. It carries no name, no email and no free text.

Narration (the AI-written reasons) happens before the save in the same call, so it is part of the creation or dispute and has no event of its own; the snapshot does not contain reasons.

`FairLoanCheckService` no longer saves outside a transaction: the narrator and PayPal calls happen first, then save and publish share one `TransactionTemplate`, which joins a caller's transaction when there is one (the loan application). With no database (the standalone unit tests) it saves and publishes directly.

### 4. What the member sees of their assessment
`UnderwritingFacade.latestAssessmentStatus(memberId)` returns `AssessmentStatusView(assessmentId, decision, memberDisputed, createdAt)`: the decision (`PENDING`, `APPROVED`, `ADJUSTED`, `DECLINED`), no score, no tier and no reasons.

### 5. The member Home summary lives in `app`
`GET /api/v1/members/me/home` (member role) returns everything the Home page needs in one call. The draft is `contracts/openapi/pending/home.yaml`.

**Why `app`.** The page is a read model across six modules and belongs to none of them. Putting it in one of them would make that module import five others (and the credit record already must not import lending or hardship, [ADR 0014](0014-credit-record-projection-levels-and-signed-share-links.md)); putting it in `desk-query` would mix the officer's read side with the member's. `app` is the wiring module: it may depend on every module's `api`, nothing depends on it, so it cannot create a cycle, and `RecordFeed` already sets the precedent. The code is in `com.paypalseed.app.home`: a controller, `MemberHomeService`, the `HomeSections` interface and its facade-backed implementation `FacadeHomeSections`.

**By construction it is the caller's page.** The route has no member id and takes no parameters. The controller reads the member from the `Actor` (`requireMember`: staff and the service get `403`, no credential gets `401`) and passes only that id down. A `memberId` in the query string or a member id in the path has no effect (the first is ignored, the second matches no route).

**Sections are independent.** `profile` (identity: display name, language, wallet), `savings` (streak, monthly saving, total saved, missed deposits, microloan eligibility, goals), `lending` (eligibility, the active loan with its next payment and autopay state, an open application), `underwriting` (income consent, latest assessment status), `creditRecord` (level, since, next-step hint) and `hardship` (offer waiting, case id, status). Each is read on its own. If one throws, or has nothing (for example a demo member with no identity record), it is `null` (omitted from the JSON) and `sectionErrors` gets `{section, code}` with `code` one of `no-data` (the module has nothing for this member; a `NOT_FOUND` from a module counts) or `unavailable` (the module failed). The rest of the page is returned with `200`. Only the stable code leaves the service; the exception is logged by type, never its message or a stack trace.

**Rules kept.** Money is `{cents, currency}`, copied from the facades. Dates are the business dates the facades return; nothing here parses a timezone. The record level is a published level, not a score. No hardship risk level, points or signals, no fair-check score or tier are read at all (AGENTS.md rule 4); a test asserts the serialized page contains none. The credit record's "next step" hint is a fixed English or Spanish template filled from the counts the record computed (`NextStepHint`); the member's language comes from the profile, English without one. No model writes it.

**Transactions.** Sections run one after another in one read-only transaction, so they describe one moment. Each section runs in a savepoint, so a section that fails with a database error (which poisons a PostgreSQL transaction) is rolled back alone and the next section still works. The outer transaction always ends in a rollback: it wrote nothing, and a facade that threw has already marked it rollback-only, which would otherwise show as an error at commit for an otherwise good page. There is no shared mutable state, so the calls could be moved to virtual threads later; they are not, because six small reads do not need it and the savepoints need one connection.

**Limits.**
- It reads only public facades, so it can show only what they expose. Today `SavingsFacade`, `LendingFacade` and `HardshipFacade` answer by id (loan, case) and not by member, so goals, eligibility, the member's loans and applications, and the member's open hardship case are read through `MemberReads`, an interface in `app.home` whose methods each name the facade call that should back it. **No production bean implements it yet.** Until one does, `lending` and `hardship` are `unavailable`, and `savings` is returned without `goals` (omitted, not empty: empty means no goals). `MemberHomeIT` supplies an adapter built from those modules' own services to prove the composition on real data.
- One call is one transaction's worth of reads per page view. It is cheap now; if a section grows, cache it per member for a few seconds rather than widening the transaction.
- It is a snapshot of facades, not a projection: it is never more current than they are, and it does not paginate. Lists in it (goals, reasons) are short by nature.

## Settings
| Key | Default | Meaning |
| --- | --- | --- |
| `seed.underwriting.income.source` | `SEEDED` | `SEEDED` or `TRANSACTION_SEARCH` |
| `seed.underwriting.income.max-age` | `24h` | Weeks older than this are read again (must be positive) |
| `seed.underwriting.income.timeout` | `30s` | Total deadline for one PayPal income read (must be positive) |
| `seed.paypal.transaction-search.enabled` | `false` | Payments' own switch ([ADR 0013](0013-paypal-payouts-billing-catalog-transaction-search-and-venmo.md)); must be `true` for `TRANSACTION_SEARCH` to work |

## Migrations
`underwriting/V4__income_refresh.sql`: `income_consent.income_refreshed_at`, `income_consent.income_anchor`.

## Errors
| `type` | Status | When |
| --- | --- | --- |
| `income-source-unavailable` | 503, retryable | PayPal could not be read; nothing was assessed |
| `paypal-account-not-linked` | 422 | The member has no identity record (never signed in with PayPal) |

`consent-required` (422) is unchanged. The home route adds no new problem types.

## Needed from other modules
- **identity:** annotate `com.paypalseed.identity.api.events` with `@NamedInterface("api-events")` in its `package-info.java`, like the other modules. Without it Spring Modulith rejects any module that listens to `ConsentRevoked`, which is why `:architecture-tests:test` is red on one rule until it is added. Also expose the payer id to other modules (for example `Optional<String> paypalPayerId(UUID memberId)` on `IdentityFacade`) before partner reading is switched on.
- **lending:** per-member reads on `LendingFacade`: the member's eligibility, loans and applications.
- **savings:** the member's goals on `SavingsFacade`.
- **hardship:** the member's open case on `HardshipFacade`.
- **desk-query:** one listener for `AssessmentChanged` (see ADR 0015).

## Alternatives considered
- **Read PayPal for every assessment.** Simple, but it sends the same 91-day query for each click and makes every assessment as slow as PayPal. A day-old cache is what a weekly summary needs.
- **Fall back to the stored or stated income when PayPal fails.** Rejected: the point of the real source is that a decision rests on evidence. A retryable refusal costs the member a retry; a decision on stale numbers costs trust.
- **Calendar (Monday to Sunday) weeks.** Would leave partial first and last weeks and does not line up with the 13 buckets the scorecard already reads. Rolling buckets cut for the read date keep the seeded and real data identical in shape.
- **Let identity own the consent underwriting enforces.** Rejected in ADR 0012 and unchanged: a consent that gates a read is enforced where the data is read.
- **Port plus adapter in `app` for identity and payments.** Used by the credit record to avoid a cycle. Here there is no cycle, so `underwriting` calls the two `api` packages directly through two small ports that only isolate the timeout and the account lookup.
- **Put the Home summary in a module.** Rejected above. A client-side composition of six calls was rejected because the browser never calls the backend and each call would cost a signed token and a round trip.
- **Run the sections in parallel on virtual threads.** Rejected for now: six reads are fast, a shared read-only transaction is what makes the page one moment in time, and savepoints need one connection.

## Consequences
- With `TRANSACTION_SEARCH` a fair check is only as available as PayPal; the 503 is explicit and retryable instead of silently weaker.
- The fair check stops the moment the member withdraws income read in identity, and the cached copy of their PayPal income is deleted.
- The desk read model can fill from the live system as soon as `desk-query` adds its listener.
- The Home page is one request. Until the three facades expose per-member reads, its lending and hardship sections report `unavailable`, and savings omits goals.
