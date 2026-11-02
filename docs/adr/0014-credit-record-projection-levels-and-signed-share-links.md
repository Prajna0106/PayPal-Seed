# ADR 0014: Credit Record Projection, Levels and Signed Share Links

## Status
Proposed (F6). Builds on [ADR 0004](0004-immutable-schedule-versions-derived-status.md) (facts are append-only), [ADR 0009](0009-ai-proposes-code-computes-human-approves.md) and [ADR 0011](0011-hardship-case-lifecycle-consent-hold-and-standing.md) (asking for help never hurts standing).

## Context
Seed's promise is that saving and repaying earns a bigger loan, and that the history is the member's to carry to another lender. That needs a record that is verifiable, cannot be talked up, does not turn into a credit score, and holds no personal data a partner could misuse. Five problems have to be solved together.

1. **Where the facts come from.** Savings publishes `DepositReceivedEvent`. Lending publishes `ScheduleGenerated`, `RepaymentReceived`, `InstalmentMissed`, `LoanRepaid` and `LateBucketChanged`. Hardship publishes `HardshipApplied`. Lending will later read a member's level to size a loan. If `credit-record` consumed lending's events and `lending` called the `credit-record` facade, Spring Modulith would see a cycle (`lending -> credit-record -> lending`) and `verify()` would fail. Events do not hide a cycle from Modulith.
2. **What counts.** A month with no deposit is not an event. An instalment "paid late" is not an event either: lending publishes the money received, not which instalment it settled.
3. **Fairness.** A hardship plan is "on plan" and never a miss (ADR 0011). One late payment inside grace, or a pause in saving, must not push a member down. Only a default may lower a level.
4. **Sharing.** A partner must be able to check a claim without an account, and the member must be able to revoke it. A leaked link must not expose a person.
5. **Delivery.** Events arrive at least once and in no fixed order.

## Decision

### Wiring: credit-record imports no business module
`credit-record` depends on `platform`, `engines` and nothing else. It exposes `CreditRecordFacade` (read, `ingest`, share, verify) and one event, `RecordLevelChanged`. The facts reach it through `CreditRecordFacade.ingest(RecordFact)`, a plain record of counts and dates. The adapter is `app/RecordFeed`: the `app` module is the wiring module and may depend on every module's `api`. It listens to the savings, lending and hardship events and forwards facts.

The dependency graph stays acyclic: `lending -> credit-record` (later, for `levelOf`), `app -> {savings, lending, hardship, credit-record}`, and `credit-record -> (nothing)`. An ArchUnit rule in `architecture-tests` fails the build if any `creditrecord` class depends on `savings`, `lending`, `hardship` or `underwriting`. That also enforces ADR 0011's rule that the record never reads hardship signals: the feed forwards one marker, `HardshipApplied`, and no case opening, risk level or signal.

### Projection
An append-only fact table (`record_fact`, protected by a trigger) plus one state row per member (`member_record`), folded by a pure `RecordProjector.apply(state, fact)`. Replaying a member's facts by `seq` rebuilds the same state. A per-member advisory lock serialises writers, so two facts for one member fold one after the other.

| Fact | Source | Business key |
| --- | --- | --- |
| `SAVINGS_DEPOSIT` | `DepositReceivedEvent`, dated by `BusinessClock.businessDateFor` | `saving-deposit:<eventId>` |
| `SAVING_MONTHS_CLOSED` | the monthly close (below) | `saving-close:<member>:<yyyy-m>` |
| `LOAN_DISBURSED` | `ScheduleGenerated` | `loan-disbursed:<loanId>` |
| `INSTALMENT_PAID` (with days late) | the loan's schedule, read when `RepaymentReceived` or `LoanRepaid` arrives | `inst-paid:<loanId>:<seq>` |
| `INSTALMENT_MISSED` | `InstalmentMissed` | `inst-missed:<eventId>` |
| `LOAN_REPAID` (with missed count) | `LoanRepaid` | `loan-repaid:<loanId>` |
| `LOAN_DEFAULTED` | `LateBucketChanged` to `DPD_60_PLUS` | `loan-default:<loanId>` |
| `HARDSHIP_PLAN_APPLIED` | `HardshipApplied` | `hardship-applied:<eventId>` |

- **Instalment status comes from the schedule, not from guessing.** Lending has no per-instalment event, so the feed reads `LendingFacade.currentSchedule` and turns every instalment shown as `PAID`, `PAID_LATE` or `PREPAID` into one fact, with the days between its due date and the date it was fully paid (0 if early). The key is the loan and sequence, so the same instalment reported by two events counts once.
- **Missed saving months need a clock.** A deposit in month M after a gap marks the months in between as missed, and a scheduled job (`RecordMonthlyClose`, every six hours, `seed.jobs.enabled`) closes finished months for members who had started saving, so a miss is noticed when the month ends. A gap of more than three months in a row is not counted beyond three: a member who stopped saving is not punished forever.
- **Out-of-order delivery is safe.** A payment that arrives before the miss it settles leaves nothing open; a miss that arrives after its loan's default is part of the default, not a new debt. State keeps `loanId:seq` keys for open and settled instalments.
- **Hardship.** `HARDSHIP_PLAN_APPLIED` raises a display counter and nothing else. Instalments are judged against their current due date, so a moved date paid on time is on time.
- **Default.** Lending has no default event. A loan that ages to 61 or more days past due (`DPD_60_PLUS`) is in default. A hardship plan moves dates, so a member being helped does not reach it.

### Levels
Pure rules in `engines/rules/record` (policy `record-v1`), with jqwik properties.

| Level | On-time saving months | Loans repaid on plan | On-time share | Loan ceiling |
| --- | --- | --- | --- | --- |
| NEW | 0 | 0 | any | $0 (nothing beyond the saving streak) |
| BUILDING | 3 | 0 | 75% | $1,500 |
| STEADY | 6 | 1 | 85% | $3,000 |
| TRUSTED | 9 | 2 | 90% | $5,000 |
| EXEMPLARY | 12 | 3 | 95% | $7,500 |

Every rung above NEW also needs every missed instalment paid. An instalment paid within 5 days after its due date (the repayment-v1 grace) is on time. The first three ceilings equal `LoanTier.LEVEL_1..LEVEL_3`, so `EligibilityEngine` can replace `resolveTier` with the record level, or take `RecordLimits.limitCents(level, tierLimit)`, the larger of the two. The record only raises a limit.

- **`next(current, facts)` = max(current, evaluate(facts))**, so a level never falls with the facts. The only way down is `afterDefault()`, which returns to NEW and restarts the counters used for the level (lifetime counters keep the true history).
- **Properties tested:** more good history never lowers the evaluated level; the carried level never falls; an open late payment blocks promotion but never demotes; a limit is never negative or overflowing for any `long`; counters saturate.

### Share links
- A member creates a share with a chosen life of 1 to 30 days and a detail level (`LEVEL`, `SUMMARY`, `FULL`). The record is **frozen as a snapshot** (level, month the record began, loans on plan, on-time band, defaults at that moment). The token names the share and the snapshot; the member is only reachable server-side.
- **Token:** `v1.<keyId>.<payload>.<signature>`. The payload is 49 bytes (share id, snapshot id, issued, expiry, detail), base64url. The signature is HMAC-SHA256 over the first three parts. **The algorithm is not in the token**: it is fixed on the server, so `alg: none`, a weaker hash or a key-confusion trick has nothing to select. The key id picks the secret from a ring, so keys rotate: new tokens use the current key, old ones verify while their key is listed in `SEED_RECORD_PREVIOUS_KEYS`. Comparison is constant time (`MessageDigest.isEqual`).
- **Verification checks twice.** The signature and expiry in the token, then the database: the share exists, is not revoked, has the same snapshot, issue time, expiry and detail as the token says. Widening the detail, extending the expiry or splicing in another snapshot fails even with a valid signature.
- **Public endpoint** `GET /api/v1/records/verify/{token}`: no sign-in. It returns `valid`, `level`, and, if the member chose, the month the record began, loans repaid on plan and the on-time band, plus issued and expiry. Unknown, expired, revoked and tampered tokens all give the same 404 body (a fixed `instance`, so the token is not echoed). The response is cacheable for 5 seconds for success and failure alike, a single indexed read, so a rate limiter in front of it is cheap. `valid` is false when the member has had a default since the snapshot, so a partner does not rely on a stale answer; it never shows the new facts.
- **No personal data:** no name, email, payer id, loan amounts or member id leave the system, and the snapshot table holds none of them either (the owner key it keeps is only for revocation and validity).
- **Operations:** shares are listed without their token, revoked once (a trigger forbids any other change), limited to 25 active per member, and creation is idempotent on `Idempotency-Key` (a different body is a 409).

### Settings
| Key | Meaning |
| --- | --- |
| `SEED_RECORD_SIGNING_SECRET` (`seed.record.signing-secret`) | At least 32 bytes. Missing in `dev`, `test` or `demo`: a random key for the process. Missing elsewhere: sharing answers 503 rather than signing with a guessable key. |
| `SEED_RECORD_SIGNING_KEY_ID` (`seed.record.signing-key-id`) | Key id new tokens carry. Default `k1`. |
| `SEED_RECORD_PREVIOUS_KEYS` (`seed.record.previous-keys`) | `kid:secret,kid2:secret2`: keys that still verify. |
| `seed.jobs.record-close-ms`, `seed.jobs.record-close-initial-ms` | Interval and first delay of the monthly close (six hours, one minute). |

## Alternatives considered

- **`credit-record` listens to the other modules' events directly.** Rejected: it creates the cycle with lending. The wiring module costs one small class and keeps the graph a tree.
- **Lending asks `credit-record` through an event-fed copy of the level.** Rejected as unnecessary: `levelOf` is a plain read on the facade, which is acyclic once `credit-record` imports no business module.
- **Compute the level on every read from the facts.** Rejected: a recomputation could lower a level, and it hides the "only a default lowers a level" rule in a query. The level is stored and only moves forward through the projector.
- **A score (0 to 850).** Rejected: a number invites comparison, appeals and misuse, and the product promise is "no credit score". Levels have published, explainable thresholds.
- **A JWT for the share link.** Rejected: the header lets the token pick its algorithm, which is the root of the classic `none` and key-confusion attacks, and a JWT carries claims a partner can read. The compact token has nothing to choose and nothing to read.
- **A random opaque token looked up in the database.** A fair choice (simple, revocable). Rejected because the signed form lets a partner's edge or a cache reject garbage without a database read, and the signature binds the expiry and detail.
- **Verification reads the live record.** Rejected: a member would be judged on facts they did not choose to share, and the answer would change under a partner mid-decision. The snapshot is what was shared; `valid` flags a default after it.
- **Count a pause in saving as missed forever.** Rejected: it punishes a member who stopped saving for the same reason they may be unable to repay, long after the fact. Three months is the cap.

## Consequences
- The module graph has no cycle, and `lending` can call `levelOf` without a change to this module.
- A member's level cannot be lowered by hardship, by a payment inside grace, by missed months, or by a late instalment. The record is honest about it (the counters show the late payment) without demoting.
- One more class in `app` to maintain, and the feed's handlers must stay idempotent. Each is, on the fact key.
- Instalment sequence numbers can be renumbered by a re-amortisation. The first payment recorded for a `loanId:seq` wins, so a later instalment that reuses a number is not counted twice, and at worst under-counts a good payment. This errs against the member by a count of one and never produces a miss.
- A reversed payment (refund) does not retract an instalment fact, in line with ADR 0004's append-only rule. A reopened and re-repaid loan counts once.
- Lending's `InstalmentMissed` does not yet carry the `SLA_BREACH` attribution from ADR 0011, so the record cannot exclude a miss caused by our own late decision. This needs a field on the event; until then such a miss counts as open until paid and never lowers a level.
- A shared answer is up to 5 seconds stale after a revoke, because it is cacheable. A partner who needs instant revocation can skip the cache.
- A rate limit on the public route is an edge concern (Render) and is not implemented in the application.
- The share token is a bearer secret in a URL path. It should be short-lived and revoked when in doubt; the 404 never echoes it.
