# ADR 0015: Desk Read Models, Keyset Grids and Replayable Projections

## Status
Accepted. The loan application and fair-check queues are built and tested but wait for two events that `lending` and `underwriting` do not publish yet (see "What is not wired yet").

## Context
The officer desk runs AG Grid with the server-side row model, and a Bryntum workload view. It needs lists that stay fast and stable while officers scroll and while events keep arriving: loan applications, fair-check decisions, the loan portfolio, a portfolio summary with inclusion impact, and the approvals waiting per role.

The modules own the truth, in separate schemas. The architecture forbids the desk from reading their tables, and a join across schemas would couple every list to every module's internals. At the same time offset pagination (`LIMIT 50 OFFSET 5000`) gets slower the further an officer scrolls, and shifts rows under the officer when new ones arrive. A request that names a column ("sort by this") is the classic way to get SQL injection into a grid backend.

## Decision

**1. `desk-query` is a leaf module with its own projections.** It reads only what other modules publish in `api.events` and writes only its own schema `deskquery` (migrations `V2` onward). Nothing depends on it except the future Desk Analyst tools, through `DeskQueryFacade`.

| Event (from) | Projection |
| --- | --- |
| `ScheduleGenerated`, `ScheduleChanged`, `RepaymentReceived`, `RepaymentReversed`, `LoanRepaid`, `LoanReopened`, `LateBucketChanged`, `InstalmentMissed`, `CollectionModeChanged` (lending) | `loan_view`, `loan_instalment_view`, `loan_missed_view` |
| `HardshipCaseOpened`, `HardshipApplied`, `HardshipCaseClosed` (hardship) | `hardship_case_view`, the loan's hardship flag |
| `GoalCreatedEvent`, `DepositReceivedEvent` (savings) | `member_savings`, `member_savings_month` (saving streak) |
| `ApprovalRequested`, `ApprovalDecided` (governance) | `approval_view` (workload of hardship decisions) |
| snapshots of loan applications and assessments (see below) | `application_view`, `assessment_view` |

`payments` events are not used: lending already turns every money fact into its own events, and collection mode comes from `CollectionModeChanged`.

**2. Every write is a guarded upsert keyed by the source business key.** There is no event-id dedupe table, because no handler counts or appends. The guard depends on what the event is:

- *State with a timestamp or version* (applications, assessments by `changedAt`; schedules by version) is applied only if it is not older than the row. An equal timestamp rewrites the same values.
- *Independent column groups* of one loan each have their own "as of" business date (money, delinquency, collection mode), so a late money fact cannot block a delinquency fact.
- *Facts that only accumulate* are stored as sets: deposit months (the streak is recomputed from the set), missed instalments by `(loan, version, seq)`, hardship cases (`CLOSED` is terminal, so an `OPEN` event that arrives late cannot reopen one), approval requests (the stage only moves forward).
- *Derived columns* are recomputed from stored facts, not from the event in hand: the loan's hardship flag from the case view, the next instalment owing from the stored schedule and balance (repayments settle the oldest instalment first, [ADR 0006](0006-repayment-allocation-waterfall.md)).
- A loan row created by an early event is not listed until its `ScheduleGenerated` is seen.

Residual risk: two *different* money facts for one loan on the *same business date* have no order the desk can see (lending events carry a date, not a sequence), so the last delivered wins. Lending publishes them in one transaction in causal order and the listeners run asynchronously per event, so this needs a reordering inside one day. The replay test below covers the realistic cases, including `RepaymentReceived` and `LoanRepaid` on the same date.

**3. Lists use keyset pagination bound to the query.** The cursor is `base64url(json) "." base64url(HMAC-SHA256)`. The signature covers the list, the sort and the filters, so a cursor cannot be forged, reused with another order or filter, or used on another list; each case returns 422 `desk-query-invalid-cursor`. Without `seed.desk-query.cursor-key` each process makes a random key (fine for one instance; set the key when several `api` instances run). The position is the last row's sort values, then the row id, as a row-value comparison `(a, id) > (:a, :id)`, which PostgreSQL answers from a composite index. The tiebreak id follows the direction of the last sort column, so a single-column sort in either direction reads one index in one direction; mixed directions expand to an `OR` of prefixes. Nullable sort columns are coalesced to a sentinel so a cursor can always carry the value. A row inserted while an officer scrolls cannot shift the next page.

**4. Sort and filter are allow-lists; no request text becomes SQL.** A grid defines its sortable columns (name and type) and its filters (a fixed set of values, or a boolean). The repository maps each public name to a fixed jOOQ expression; values are bound parameters. Unknown columns, filters, values, directions, limits and cursors return 422 problems (`desk-query-invalid-sort`, `-filter`, `-limit`, `-cursor`) instead of being ignored, so a typo never shows an unfiltered list as if it were filtered. At most three sort columns.

**5. Totals do not need a full count.** The first page carries `total`: an exact count bounded by `LIMIT cap + 1` (default 10000 rows read at most), and beyond the cap the planner's estimate from `EXPLAIN (FORMAT JSON)`, flagged `exact: false`. Later pages carry none.

**6. Authorisation.** `OFFICER`, `CHECKER` or `ADMIN`; members, services and agents get 403, anonymous callers 401. Members appear only as an opaque `memberRef`. No name, email, phone or score exists in any projection, so none can leak. Responses are `Cache-Control: no-store`.

**7. Rebuild is a reset plus a replay of the Modulith event publication log.** `DeskQueryFacade.reset(projection)` empties one projection and repairs the columns other projections derive from it. The replay uses the same idempotent handlers that built it:

```sql
UPDATE event_publication SET completion_date = NULL WHERE listener_id LIKE '%deskquery%';
```

then restart the `worker` or `api` role (`spring.modulith.republish-outstanding-events-on-restart: true`), or call `IncompleteEventPublications.resubmitIncompletePublications(...)`. Publications are replayed in no particular order and in parallel; the guards make that safe, and `DeskQueryIT` proves it by resetting four projections, reopening the publications and comparing every figure. The event publication log is the only source of events for the desk, so a replay is only as complete as the log's retention. There is deliberately no internal rebuild endpoint: it could only work by reading other modules.

**8. Summary figures** (`/api/v1/desk/portfolio/summary`), all from the projections and read in one snapshot:

- Loans and cents by status, and by aging bucket for loans still owed (all five buckets, in severity order).
- *On-time repayment rate*: instalments that fell due on or before the business date, less those recorded as missed, over those due, in basis points; absent while nothing has fallen due. Missed instalments are append-only facts, so a cured late payment still counts as missed: the honest rate, not the flattering one.
- *Inclusion impact*: members served (saved or borrowed), members with a loan, members loaned to without a credit score (equal to the previous by design: no decision uses a score), loans issued, average loan size (rounded half up), first loans repaid, and first loans repaid on plan (a member's first loan is the earliest disbursed; on plan means repaid with no missed instalment), with the rate in basis points. Loans returned in the cooling-off window are not counted.

## What is not wired yet
`lending` and `underwriting` publish no event for a loan application or a fair-check assessment, so `application_view` and `assessment_view` cannot fill from the live system. Everything that reads and writes them is built and tested: `ApplicationProjector` and `AssessmentProjector` apply whole-state snapshots in any order, the endpoints and the workload view read them, and `DeskQueryIT` feeds them snapshots built from the real views of the same flows. To switch them on, the other modules publish, inside the transaction that changed the data, the two events drafted in `contracts/events/pending/desk-query.json`:

- `LoanApplicationChanged(eventId, applicationId, memberId, status, requestedAmountCents, termMonths, submittedAt, assignedOfficerRef, changedAt)` from `lending`, after submit, each approval, reject, disburse and cooling-off cancel.
- `AssessmentChanged(eventId, assessmentId, memberId, applicationId, recommended, recommendedAmountCents, officerDecision, memberDisputed, createdAt, changedAt)` from `underwriting`, after creation, a dispute and an officer decision.

Each needs one listener method in `DeskProjectionListeners` that maps the record onto `ApplicationProjector.Snapshot` or `AssessmentProjector.Snapshot` and calls `apply`. They are snapshots, not deltas, so a delivery that is repeated or arrives late does no harm.

## Alternatives considered

- **Query the modules' tables or facades per request.** Rejected: it couples the desk to every module's schema, makes each list as slow as its slowest join, and breaks "the desk reads only its own projections".
- **Offset pagination with AG Grid's `startRow`/`endRow`.** Rejected: cost grows with depth, and a new row shifts every later page, so rows repeat or vanish while an officer scrolls.
- **Unsigned cursors holding the raw last values.** Rejected: a client could build one by hand, or carry it to a list or filter it was not issued for, and get a page that means nothing.
- **Pass the grid's filter model through and build SQL from it.** Rejected: that is the injection surface this design removes. The web layer translates AG Grid's model into the small set of named parameters.
- **A rebuild endpoint under `/internal/jobs`.** Rejected as above; replay of the publication log needs no module's cooperation.
- **A materialised view refreshed on a timer.** Rejected: it adds staleness to every officer action and still needs a refresh job per view.
- **Exact `COUNT(*)` on every first page.** Rejected: it scans the whole filtered set on a table that is meant to grow.

## Consequences
- Lists answer from one table each, in constant time per page; the total costs at most `cap + 1` index entries.
- The desk is eventually consistent with the modules. Responses carry `asOf`, the time of the newest fact applied, so a screen can say how fresh it is.
- A new upstream status or bucket name is stored as received and never fails a handler; filters accept only the values this module knows, and `AgingBuckets.resolve` falls back to days past due for an unknown bucket.
- A change in a module's event (a renamed field) fails the desk's compile, not a request, because the listeners import the records.
- Replay needs the publication log; if completed publications are ever purged, a rebuild is possible only from the point retained.

## Settings (all optional)
| Key | Default | Meaning |
| --- | --- | --- |
| `seed.desk-query.cursor-key` | random per process | HMAC key for page cursors; set the same value on every `api` instance |
| `seed.desk-query.application-sla` | `PT24H` | Time an officer has for the first decision on a submitted application |
| `seed.desk-query.sla-due-soon` | `PT4H` | Window before a deadline in which an item is `DUE_SOON` |
| `seed.desk-query.default-page-size` | `50` | Rows per page when `limit` is absent |
| `seed.desk-query.max-page-size` | `200` | Largest allowed `limit` |
| `seed.desk-query.count-cap` | `10000` | Rows counted exactly before a total becomes an estimate |

## Endpoints
`GET /api/v1/desk/applications`, `/assessments`, `/portfolio/loans`, `/portfolio/summary`, `/workload`. Contract draft: `contracts/openapi/pending/desk-query.yaml`; events needed: `contracts/events/pending/desk-query.json`.
