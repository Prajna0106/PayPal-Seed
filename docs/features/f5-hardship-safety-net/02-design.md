# F5 — Technical Design

Read [03-algorithms.md](03-algorithms.md) for the maths and the worked example, and the F4 design ([../f4-repayment-autopay/02-design.md](../f4-repayment-autopay/02-design.md)) for the loan, schedule and event shapes this feature builds on. The principle that shapes everything below: **numbers from code, words from AI, decisions from humans.** The safety net must keep working with the AI switched off.

## 1. Ownership across modules

This follows the dependency rules in [STRUCTURE.md §3.2](../../STRUCTURE.md). No module reads another module's schema.

| Concern | Owner | How the others reach it |
| --- | --- | --- |
| Signals, scorecard runs, cases, offers, member consent, execution saga, case timers | `hardship` | `hardship.api.HardshipFacade` (reads), events |
| Scorecard, option ranking, policy caps, approval tiers, grounding checker, rubber-stamp detector | `engines/rules` | Plain Java, no Spring, no IO. Takes plain values in (previews, series), returns plain values |
| Schedule maths and the three reschedule primitives | `engines/schedule` (F4, built) | Only through `lending.api.LendingFacade` |
| Schedule versions, previews, applying a reschedule, grace holds, collection suppression | `lending` (F4, extended) | `LendingFacade`: `previewReschedule`, `applyReschedule`, plus the new `placeHold`, `releaseHold`, `setHardshipOpen` |
| Proposals, verdicts, maker–checker approvals, hash-chained audit log | `governance` (F7 minimal slice) | `governance.api.GovernanceFacade`; owners register a `ProposalValidator` |
| Read tools, proposal intake, scoped agent tokens, untrusted-text extractor, kill switch, agent run log | `agent-gateway` | HTTP for agents; in-process for the kill-switch check |
| The Guardian agent (runs outside the JVM or in the worker role) | `agents/guardian` | HTTP to `agent-gateway` only, with a scoped token. Never calls a write endpoint |
| Consented income summary | `underwriting` (F3) | `UnderwritingFacade.incomeSummary(memberId)`: weekly totals only, no counterparties |
| SMS and in-app messages, STOP handling | `engagement` | Consumes hardship events; replies arrive through the F4 Zapier inbound endpoint |
| Hardship queue grid and KPIs | `desk-query` | Consumes hardship events into its own projection |
| "On plan" standing | `credit-record` (F6) | Consumes `HardshipApplied` and `HardshipCaseClosed` |
| PayPal subscription revise or suspend | `payments` (F4) | Unchanged: it reacts to `ScheduleChanged` and to the loan's desired mandate |

```text
 agents/guardian ──HTTPS, scoped token──► agent-gateway ──read──► lending.api · hardship.api · underwriting.api
 (Astropods or worker)                         │
                                               │ submit_proposal (untrusted)
                                               ▼
 desk (AG Grid) ─► hardship.web ─────────► governance ──► ProposalValidator SPI ──┐
 member app   ─►  hardship.web                │ approval_request, approvals,       │ implemented by
                       │                      │ audit hash chain                   ▼ hardship
                       │ facade calls (in process, same transaction)
                       ▼
             lending.api  ◄── engines/schedule (pure)        hardship ──► engines/rules (pure)
             (preview, apply, hold, flag)

 Dependency direction:  hardship → lending, governance, underwriting, engines.
                        agent-gateway → hardship, lending, governance (read and submit only).
                        governance → nobody (it defines the SPI; hardship implements it).
                        lending → nobody new.
 Events fan out the other way: lending events → hardship; hardship events → engagement, desk-query, credit-record.
```

**Why `lending` never depends on `hardship`.** F4 must keep working in a build that has no F5. Lending therefore exposes small commands and never imports a hardship type. Hardship pushes the two facts lending needs, in the same transaction as the case change:

- `LendingFacade.setHardshipOpen(loanId, caseRef, open)`: a flag that suppresses the automatic retry and the grace nudge while a case is open. This is the "open hardship case" check in F4 [01-requirements AC3](../f4-repayment-autopay/01-requirements.md). The brief for this feature described the flag as event-fed. A facade command with `MANDATORY` propagation, like `ledger.post`, is better: it commits with the case row, so there is no window in which a case is open and the flag is not, and lending needs no import from hardship's event package.
- `LendingFacade.placeHold(loanId, seq, until, caseRef)` and `releaseHold(caseRef)`: a grace hold ([03 §9](03-algorithms.md#9-grace-hold)). Lending owns the data and its close of business reads its own table.

**Why governance is generic.** Governance knows proposals, verdicts, approvals and the audit chain. It does not know what a hardship case is. Each owning module registers a `ProposalValidator` for its proposal kind (dependency inversion), so `governance` never imports `hardship`.

### 1.1 Changes to F4 (small, additive)

| Change | Where | Why |
| --- | --- | --- |
| Migration `lending` V3: `loan_hold`, `loan_hardship_flag`, a nullable `attribution` on `instalment_missed`, and a partial unique index on `schedule_version (loan_id, caused_by) WHERE reason <> 'ORIGINAL'` | `modules/lending` | Hold, open-case flag, miss attribution (section 5) and idempotent apply |
| **Required change:** `RescheduleService.apply` becomes idempotent on `approvalRef`. Today it always inserts a new schedule version, so a replayed approval would create a second one. The fix looks up `caused_by = 'approval:' + approvalRef` under the loan lock and returns the existing version | `RescheduleService`, `LendingFacade.applyReschedule` | The saga must be safe to retry. This is task T3 in the implementation plan, tested by TC-HEX-02 and TC-HEX-16 |
| **Required change:** `applyReschedule` also takes the approved proposed rows and, under the loan lock, recomputes the proposal and refuses with `RESCHEDULE_PREVIEW_CHANGED` if it differs | `LendingFacade.applyReschedule` | Makes the snapshot-hash check atomic ([03 §8](03-algorithms.md#8-stale-preview-detection)) |
| `LendingFacade.placeHold`, `releaseHold`, `setHardshipOpen` | `lending.api` | Commands from hardship |
| COB: while a hold covers the instalment, do not write `InstalmentMissed`; the miss is written at the first COB after the hold ends | `CloseOfBusiness` | Grace hold ([03 §9](03-algorithms.md#9-grace-hold)) |
| Retry and grace-nudge planners skip loans with a hardship flag | `CollectionService`, `MemberMessageTriggers` (engagement) | F4 AC3 |
| `ReschedulePrimitive` stays as built (`MOVE_DATE`, `DEFER_TO_END`, `REAMORTIZE`) | — | Option D is a payment, not a reschedule |

A hold does **not** suspend the PayPal subscription. A scheduled autopay charge can still land while a request waits; the failure table (section 11) says how that is handled.

## 2. How F5 consumes F4

| F4 event or call | Used for |
| --- | --- |
| `InstalmentDueSoon` (due − 3), `GraceNudgeDue` (grace day 3) | Wake-up for a targeted scan of one loan, in addition to the daily scan |
| `RepaymentFailed` (`seq`, `attemptNo`, `reason`) | Signal S3; escalates an open WATCH case |
| `LateBucketChanged`, `InstalmentMissed` | Signal S5; also closes the window for a hold |
| `RepaymentReceived`, `LoanCured`, `LoanRepaid` | `CANCELLED_PAID` when the member catches up alone; closes option D |
| `ScheduleChanged` | Detects a schedule change made outside the case (stale preview, `SUPERSEDED`) and confirms that our own apply landed |
| `LendingFacade.loan`, `currentSchedule` | Reads for the scan, tool answers and the evidence panel |
| `LendingFacade.previewReschedule(RescheduleRequest)` | Every option number, with no writes |
| `LendingFacade.applyReschedule(RescheduleRequest, approvalRef)` | The only write, from the saga, keyed by `approvalRef` |

`RescheduleRequest(loanId, primitive, seq, days, extraMonths)` is the F4 shape: `MOVE_DATE` uses `seq` and `days`, `DEFER_TO_END` uses `seq`, `REAMORTIZE` uses `extraMonths`. The business rules are in `Rescheduler` ([F4 03 §10](../f4-repayment-autopay/03-algorithms.md#10-reschedule-primitives-called-by-f5)). F5 never reimplements them.

## 3. State machines

### 3.1 Hardship case

Terminal states: `APPLIED`, `DECLINED_BY_MEMBER`, `REJECTED_BY_OFFICER`, `EXPIRED`, `CANCELLED_PAID`, `SUPERSEDED`. `APPROVED_EXECUTION_FAILED` is retryable.

| From | To | Trigger | Actor | Guard |
| --- | --- | --- | --- | --- |
| — | `DETECTED` | Daily scan or event wake-up | SERVICE | Eligible loan, level at least LIKELY_MISS, no open case, outside the 14-day cool-down, no pending PayPal money |
| — | `OFFER_VIEWED` | Member asks for help in the app | MEMBER | Loan active, no open case |
| — | `OUTREACH_SENT` | Member sends `HELP` by SMS | MEMBER | Same; the reply carries the offer link |
| `DETECTED` | `OUTREACH_SENT` | Offer issued and handed to engagement | SERVICE | Offer exists; limits and quiet hours applied by engagement |
| `OUTREACH_SENT` | `OFFER_VIEWED` | Member opens the offer | MEMBER | Offer ACTIVE |
| `OUTREACH_SENT`, `OFFER_VIEWED` | `OPTION_CHOSEN` | Member picks an option | MEMBER | Offer ACTIVE, not stale, policy checks pass, idempotent |
| `OPTION_CHOSEN` | `APPROVED` | Policy approval (tier `AUTO_POLICY`, option A) | SYSTEM | Policy v1 caps pass, `snapshot_hash` current |
| `OPTION_CHOSEN` | `PENDING_APPROVAL` | Approval request opened (tier `MAKER_CHECKER`, options B and C) | SYSTEM | Same checks |
| `PENDING_APPROVAL` | `APPROVED` | Maker and checker both approve | CHECKER | SoD holds, `snapshot_hash` current |
| `PENDING_APPROVAL` | `REJECTED_BY_OFFICER` | Either decision is reject, with a reason code | OFFICER or CHECKER | Reason code present |
| `OPTION_CHOSEN`, `PENDING_APPROVAL` | `OFFER_VIEWED` | Preview went stale; approvals voided; new offer revision | SYSTEM | `snapshot_hash` changed |
| `APPROVED` | `APPLIED` | Saga step 2 succeeds | SYSTEM | `applyReschedule` returned a version |
| `APPROVED` | `APPROVED_EXECUTION_FAILED` | Saga retries exhausted (5 attempts, backoff) | SYSTEM | — |
| `APPROVED_EXECUTION_FAILED` | `APPROVED` | Desk retry button | OFFICER | — |
| Any non-terminal before `APPROVED` | `DECLINED_BY_MEMBER` | Member declines or withdraws | MEMBER | Not yet `APPROVED` |
| `DETECTED`, `OUTREACH_SENT`, `OFFER_VIEWED` | `EXPIRED` | `OFFER_EXPIRY` timer (the last day of grace for the target instalment, due + 5) | SYSTEM | No choice made |
| Any non-terminal | `CANCELLED_PAID` | `RepaymentReceived` clears the target instalment, or the member's option D payment lands | SYSTEM | Target instalment remaining = 0 |
| Any non-terminal | `SUPERSEDED` | The schedule was changed by another route, or a newer case replaces this one | SYSTEM or OFFICER | `ScheduleChanged` with a cause not from this case |

`PENDING_APPROVAL` never expires on its own: humans must decide. The SLA timers escalate instead ([03 §10](03-algorithms.md#10-sla-timers)).

### 3.2 Governance approval request

`OPEN → APPROVED | REJECTED | EXPIRED | VOIDED`. `VOIDED` covers a stale preview or a withdrawn choice. Each decision is a separate append-only `approval` row.

## 4. Core flows

Every step that changes state commits its row, its outbox event and any facade call in **one transaction**.

### 4.1 Detect (daily scan and event wake-ups)

1. `workflows/hardship-scan` (Render Workflows) fans out one step per shard group and calls `POST /internal/jobs/hardship/scan {businessDate, shardGroup, shardCount, after, limit}`. The step uses the F4 shard groups (`LoanRow.shardsOfGroup`) and keyset paging on `loan_id`.
2. For each eligible loan (active, with an instalment due in the next 10 days or in grace, no open case, outside the 14-day cool-down after the last case closed; a member's own `HELP` is never blocked by the cool-down), hardship reads the schedule and aging from lending and the weekly inflow series from `underwriting`.
3. `engines/rules` extracts features and computes the scorecard ([03 §3](03-algorithms.md#3-the-scorecard)). A level rises at once and drops only after two consecutive scans below its threshold (hysteresis). The result is stored as an append-only `scorecard_run` with its `signal` rows, when the points are above zero or the level changed. `UNIQUE (loan_id, scan_date)` makes the scan idempotent.
4. At WATCH or above, with a borderline score, the optional AI second opinion runs ([03 §4](03-algorithms.md#4-ai-second-opinion-escalate-only)). It can raise the level by one step, never lower it.
5. At LIKELY_MISS or above, hardship opens a case (`DETECTED`), calls `LendingFacade.setHardshipOpen(..., true)`, and emits `HardshipCaseOpened` and `HardshipSignalDetected`.

### 4.2 Propose and validate

1. Hardship builds the option set ([03 §5](03-algorithms.md#5-option-generation)): up to ten previews through `previewReschedule`, ranked by `OptionRanker`. The result is stored as an immutable `offer` with its `offer_option` rows and one `snapshot_hash` per option.
2. The Guardian agent is asked for words: an officer brief and the member message. It reads through `agent-gateway` tools with a scoped token. If the kill switch is on, the budget is spent or the model chain fails, hardship uses the template copy and the flow continues.
3. The agent calls `submit_proposal` (kind `HARDSHIP_OUTREACH`, idempotency key = run id). `governance` stores the proposal and calls the hardship `ProposalValidator`. The validator **recomputes every number from source data**, runs the grounding check on the narrative and re-checks policy and fairness rules. It returns APPROVABLE or REJECTED with reasons. Nothing the agent says is trusted.
4. REJECTED means the narrative is replaced by the template. The offer itself is unaffected because its numbers came from code.

### 4.3 Outreach

1. Hardship emits `HardshipOfferIssued`. Engagement applies quiet hours (20:00 to 08:00 member local time), the limits (one proactive outreach per instalment, two per 30 days) and STOP, then sends the SMS through Zapier or shows the in-app card. Case status becomes `OUTREACH_SENT`. If the member has not answered by due − 1 day, an `OFFER_REMINDER` timer sends one reminder. There is never more than one outreach and one reminder per instalment.
2. The SMS carries one link to the offer page. Replies `HELP`, `YES` and `NO` use the F4 inbound endpoint and its closed keyword set. Free text goes through the tool-less extractor, which returns only a reason category.
3. The member opening the page moves the case to `OFFER_VIEWED`.

### 4.4 Choose

1. `POST /api/v1/hardship/cases/{caseId}/choice` carries `offerId`, `optionId` and an `Idempotency-Key`. Hardship checks that the offer is ACTIVE and not expired, re-runs the policy checks and recomputes the preview. If the recomputed `snapshot_hash` differs from the stored one, the offer is marked `STALE`, a new revision is issued and the member must confirm again (`409 hardship-offer-stale`).
2. The choice is stored as an append-only `choice` row (consent). A duplicate tap returns the same result.
3. Tier decides the next step. Option A (tier `AUTO_POLICY`): policy approves at once and the case moves to `APPROVED`. Options B and C (tier `MAKER_CHECKER`): hardship creates a governance `approval_request` bound to the offer and the `snapshot_hash` and the case moves to `PENDING_APPROVAL`. Option D opens a payment (F4 `POST /payments`) and the case ends when the money lands.
4. If the chosen option is B or C and the grace period ends within 3 days, hardship calls `placeHold`. A `HOLD_CHECK` timer at due + 3 places the hold later for a choice made earlier and still undecided. The hold suppresses only the `InstalmentMissed` fact: the days past due and the bucket are still computed from the dates ([03 §9](03-algorithms.md#9-grace-hold)).

### 4.5 Approve (maker and checker)

1. The officer opens the case in the desk queue. The evidence panel shows the signals with their numbers, the option previews, the member's choice, the narrative and the source numbers beside it. Opening the panel is recorded (`evidenceViewedMs`).
2. The maker posts `decision {decision, reasonCode, note}`. A reject ends the request. An approve waits for the checker.
3. The checker reviews and posts a decision. SoD is enforced by the database: the checker is not the maker, and neither is the member. Both decisions are bound to `offerId` and `snapshot_hash`. If the preview went stale in between, the request is voided and the member re-confirms.
4. Every step appends to the audit hash chain. When the last required decision lands, governance emits `ApprovalDecided`.

### 4.6 Execute (saga)

The saga runs after `ApprovalDecided(APPROVED)`. It is designed to be idempotent on `approvalRef = caseId:optionId:requestId`, which depends on the F4 change in section 1.1.

1. Re-preview and compare `snapshot_hash`. A mismatch aborts to re-confirmation (no change has been made yet).
2. `LendingFacade.applyReschedule(request, approvalRef, approvedRows)`: a new schedule version in one transaction. A repeat with the same `approvalRef` must return the same version. That is **not how F4 behaves today** (it always inserts a new version), so idempotent apply is a required F4 change (section 1.1).
3. F4's existing chain does the rest: `ScheduleChanged` → mandate sync (revise quantity, or keep suspended and reactivate) → pending retries cancelled.
4. `releaseHold`, `setHardshipOpen(false)`.
5. Emit `HardshipApplied`: credit-record marks the instalments "on plan", engagement sends the confirmation, desk-query and the Bryntum SSE refresh.
6. Case status `APPLIED`, then `HardshipCaseClosed`.

A failure in steps 2 to 4 leaves the case in `APPROVED_EXECUTION_FAILED` with a retry button in the desk. Step 2 is atomic, so there is never a half-applied schedule.

### 4.7 Member asks for help (`HELP`)

`POST /api/v1/hardship/cases` or the SMS keyword `HELP` opens a case with origin `MEMBER_HELP` at level IMMINENT, no matter the score. Offers are generated immediately with template copy. The AI brief is added for the officer when it is ready. The member is never made to wait for a model.

### 4.8 AI switched off

If `agent-gateway` reports the kill switch, the budget is spent or the circuit breaker is open, hardship skips steps 2 and 3 of section 4.2. Scores, options, template messages, approvals and execution are unchanged. Officers see `narrative.source = TEMPLATE`.

## 5. Data model

Money is `bigint` cents. Dates are business dates in `America/Los_Angeles`. Migrations follow F4: one Flyway location per module, jOOQ classes generated per module (ADR 0008). **Append-only** tables get a `BEFORE UPDATE OR DELETE` trigger that raises `restrict_violation`, like `lending.reject_mutation`.

### 5.1 `hardship` schema

| Table | Kind | Purpose and key constraints |
| --- | --- | --- |
| `hardship.case` | state | `id` PK, `loan_id`, `member_id`, `target_seq`, `base_version`, `origin` (`PROACTIVE_SCAN`, `MEMBER_HELP`, `STAFF`), `status` CHECK in the state list, `risk_level`, `score`, `policy_version`, `scorecard_version`, `dedupe_key UNIQUE` (`loan:<id>:v<ver>:s<seq>`), `opened_on`, `closed_on`, `closed_reason`, `cooldown_until` (closed_on + 14 days; null for `MEMBER_HELP`), `row_version`. **`CREATE UNIQUE INDEX case_one_open_per_loan ON hardship.case (loan_id) WHERE status NOT IN ('APPLIED','DECLINED_BY_MEMBER','REJECTED_BY_OFFICER','EXPIRED','CANCELLED_PAID','SUPERSEDED')`**. CHECK `(closed_on IS NULL) = (status NOT IN (terminal))` |
| `hardship.scorecard_run` | append-only | One evaluation of one loan on one date: `loan_id`, `scan_date`, `level`, `points`, `features jsonb`, `feature_hash`, `policy_version`. `UNIQUE (loan_id, scan_date)` makes the scan idempotent |
| `hardship.signal` | append-only | `run_id` FK, `code`, `points`, `evidence jsonb` (the numbers), `source` (`SCORECARD`, `AI_SECOND_OPINION`, `MEMBER`). `UNIQUE (run_id, code)`. An AI row must carry `run_ref` to `agentgateway.agent_run` |
| `hardship.offer` | state | `id`, `case_id`, `revision` (`UNIQUE (case_id, revision)`), `base_version`, `base_allocation_hash`, `issued_on`, `expires_on`, `status` (`ACTIVE`, `STALE`, `EXPIRED`, `ACCEPTED`, `SUPERSEDED`). At most one ACTIVE per case: partial unique index on `(case_id) WHERE status = 'ACTIVE'` |
| `hardship.offer_option` | append-only | `offer_id`, `label` (`A`..`D`), `primitive` (`MOVE_DATE`, `DEFER_TO_END`, `REAMORTIZE`, `PAYMENT`), `args jsonb`, `preview jsonb` (resulting instalments), `snapshot_hash`, `mean_delay_days numeric(7,2)`, `max_instalment_cents`, `burden_bps`, `sustainable bool`, `recommended bool`, `tier`, `policy_ok bool`, `warnings jsonb`. `UNIQUE (offer_id, label)`; at most one `recommended` per offer |
| `hardship.choice` | append-only | Member consent: `case_id`, `offer_id`, `option_id`, `kind` (`CHOOSE`, `DECLINE`), `member_id`, `channel` (`SMS`, `APP`), `consent_text_version`, `chosen_at`, `idempotency_key UNIQUE`. `UNIQUE (offer_id) WHERE kind = 'CHOOSE'` |
| `hardship.narrative` | append-only | `case_id`, `audience` (`MEMBER`, `OFFICER`), `locale`, `text`, `source` (`TEMPLATE`, `AI`), `grounding` (`PASSED`, `FAILED_FELL_BACK`, `NOT_RUN`), `run_ref`, `created_at` |
| `hardship.case_timer` | state | `case_id`, `kind` (`OFFER_REMINDER`, `OFFER_EXPIRY`, `HOLD_CHECK`, `SLA_50`, `SLA_80`, `SLA_BREACH`, `HOLD_EXPIRY`), `fire_at timestamptz`, `fired_at`. PK `(case_id, kind)`. Partial index on `(fire_at) WHERE fired_at IS NULL` is the sweep queue |
| `hardship.saga` | state | `case_id` PK, `approval_ref UNIQUE`, `step`, `status`, `attempts`, `last_error`, `next_attempt_at` |
| `hardship.decision_stat` | projection | Rolling per-officer decision statistics for the rubber-stamp detector ([03 §12](03-algorithms.md#12-rubber-stamp-detector)). Rebuilt from `governance.approval` events |

### 5.2 `governance` schema

| Table | Kind | Purpose and key constraints |
| --- | --- | --- |
| `governance.proposal` | append-only | `id`, `kind` (`HARDSHIP_OUTREACH`, `HARDSHIP_RESCHEDULE`), `subject_id` (case id), `payload jsonb`, `payload_hash`, `submitted_by` (agent run id or `service`), `idempotency_key UNIQUE`. Same key with a different `payload_hash` is refused (`409 idempotency-key-reused`) |
| `governance.verdict` | append-only | `proposal_id`, `result` (`APPROVABLE`, `REJECTED`), `checks jsonb` (each check with pass or fail and the values compared), `validator_version`. `UNIQUE (proposal_id)` |
| `governance.approval_request` | state | `id`, `proposal_id`, `subject_id`, `subject_member_id`, `tier` (`AUTO_POLICY`, `MAKER_CHECKER`), `offer_id`, `snapshot_hash`, `status` (`OPEN`, `APPROVED`, `REJECTED`, `EXPIRED`, `VOIDED`), `sla_due_at`, `opened_at`, `closed_at`. One OPEN request per subject (partial unique index) |
| `governance.approval` | append-only | One decision: `request_id`, `role` (`MAKER`, `CHECKER`), `staff_id`, `staff_member_id` (null unless the staff user is also a member), `decision` (`APPROVE`, `REJECT`), `reason_code`, `note`, `evidence_viewed_ms`, `decided_at`. `UNIQUE (request_id, role)`. **A deferred constraint trigger enforces SoD**: the CHECKER's `staff_id` differs from the MAKER's, and `staff_member_id` differs from `approval_request.subject_member_id`. CHECK that `reason_code` is not null for REJECT. A CHECKER row cannot exist without a MAKER APPROVE |
| `governance.audit_entry` | append-only | The hash chain: `seq bigint identity` PK, `entry_type`, `subject_id`, `actor_type`, `actor_id`, `payload jsonb`, `prev_hash bytea`, `hash bytea`, `created_at`. `hash = SHA-256(prev_hash ‖ canonical_json(entry))`, computed by the application. A `BEFORE INSERT` trigger takes `pg_advisory_xact_lock` on the chain, checks that `prev_hash` equals the last row's `hash` and rejects the insert otherwise, so concurrent writers cannot fork the chain. A nightly job re-computes the whole chain |

### 5.3 `agentgateway` schema

| Table | Kind | Purpose and key constraints |
| --- | --- | --- |
| `agentgateway.agent_run` | state, then append-only once `finished_at` is set | `id`, `agent`, `case_id`, `loan_id`, `prompt_hash`, `model`, `model_chain jsonb`, `tool_calls jsonb` (name, latency, argument hash), `tokens_in`, `tokens_out`, `cost_micro_usd`, `latency_ms`, `grounding_result`, `verdict`, `error_code`, `started_at`, `finished_at`. The row stores hashes, never prompt text |
| `agentgateway.agent_token` | state | `jti` PK, `run_id`, `loan_id`, `case_id`, `scopes text[]`, `expires_at` (at most 10 minutes after issue, CHECK), `revoked_at` |
| `agentgateway.kill_switch` | state | `scope` PK (`GLOBAL` or an agent name), `enabled`, `reason`, `changed_by`, `changed_at`. Every change is also appended to `kill_switch_event` (append-only) |
| `agentgateway.usage_budget` | state | `day` PK, `tokens_used`, `cost_micro_usd`, `calls`. The gateway refuses a run once the day's cap is reached |

### 5.4 Added to `lending` (migration V3)

| Table | Kind | Purpose and key constraints |
| --- | --- | --- |
| `lending.loan_hold` | state, rows never deleted | `id`, `loan_id`, `seq`, `version`, `placed_on`, `until`, `cause` (`CHOSEN_OPTION_PENDING`, `SLA_ESCALATION`), `case_ref`, `released_on`, `released_reason`. Partial unique index on `(loan_id, seq) WHERE released_on IS NULL`. CHECK `until <= due_date + 10` (the miss date is due + 6; a hold covers at most 3 days from it, plus one audited 2-day extension) |
| `lending.loan_hardship_flag` | state | `loan_id` PK, `case_ref`, `since`. Row present while a case is open |

## 6. API

Contracts go into `contracts/openapi/seed-api.yaml`, `internal-jobs.yaml` and `agent-tools.yaml` (see [04-agent-architecture.md](04-agent-architecture.md) for the agent side). Errors use RFC 9457 `application/problem+json` with `type = https://problems.paypal-seed.dev/{code}`. Every write accepts an `Idempotency-Key`. Reusing a key with a different body hash returns `409 idempotency-key-reused`.

### 6.1 Member (`role=member`; ownership checked in the use case; another member's case returns 404)

| Method and path | Purpose |
| --- | --- |
| `GET /api/v1/loans/{loanId}/hardship` | The open case and its active offer, or `404 hardship-no-open-offer` |
| `POST /api/v1/hardship/cases` | Ask for help. Body `{loanId, reasonText?}`; the text goes only to the extractor |
| `POST /api/v1/hardship/cases/{caseId}/choice` | `{offerId, optionId}`, idempotent |
| `POST /api/v1/hardship/cases/{caseId}/decline` | One tap, no reason needed |

Example `GET /api/v1/loans/4f1c…/hardship` (Ana, scan date 2027-03-10, figures from [03 §13](03-algorithms.md#13-worked-example-ana)):

```json
{
  "caseId": "9b2e…", "status": "OFFER_VIEWED", "riskLevel": "LIKELY_MISS",
  "target": { "seq": 3, "dueDate": "2027-03-20", "amountCents": 15000 },
  "offer": {
    "offerId": "c71a…", "revision": 1, "expiresOn": "2027-03-25", "recommended": "C",
    "message": { "locale": "en", "source": "AI", "text": "Your sales look lower than usual. Here are four ways to make March easier. You choose, and no fee applies to any of them." },
    "options": [
      { "optionId": "o-a…", "label": "A", "primitive": "MOVE_DATE", "args": { "seq": 3, "days": 14 },
        "summary": { "movedFrom": "2027-03-20", "movedTo": "2027-04-03", "amountCents": 15000, "lastDueDate": "2027-08-20", "maxInstalmentCents": 15000 },
        "tier": "AUTO_POLICY", "sustainable": false, "warnings": [] },
      { "optionId": "o-b…", "label": "B", "primitive": "DEFER_TO_END", "args": { "seq": 3 },
        "summary": { "removedFrom": "2027-03-20", "addedOn": "2027-09-20", "amountCents": 15000, "lastDueDate": "2027-09-20", "maxInstalmentCents": 15000 },
        "tier": "MAKER_CHECKER", "sustainable": false, "warnings": ["NOT_SUSTAINABLE_AT_CURRENT_INCOME"] },
      { "optionId": "o-c…", "label": "C", "primitive": "REAMORTIZE", "args": { "extraMonths": 2 },
        "summary": { "instalments": 8, "firstDueDate": "2027-03-20", "amountsCents": [11200, 11300], "lastDueDate": "2027-10-20", "maxInstalmentCents": 11300 },
        "tier": "MAKER_CHECKER", "sustainable": true, "warnings": [] },
      { "optionId": "o-d…", "label": "D", "primitive": "PAYMENT", "args": { "amountCents": 6900 },
        "summary": { "payNowCents": 6900, "stillDueOn": "2027-03-20", "stillDueCents": 8100 },
        "tier": "NONE", "sustainable": false, "warnings": ["LEAVES_SHORTFALL"] }
    ]
  }
}
```

Every number in `summary` comes from the engine preview. The `message.text` is the only AI-written field and it passed the grounding check, or the field is the template and `source` is `TEMPLATE`.

Example choice response (option B):

```json
{ "caseId": "9b2e…", "status": "PENDING_APPROVAL", "optionLabel": "B", "approval": { "tier": "MAKER_CHECKER", "decisionDueAt": "2027-03-12T09:42:00-08:00" }, "hold": null }
```

### 6.2 Desk (`role=officer` is the maker, `role=checker` is the checker)

| Method and path | Purpose |
| --- | --- |
| `GET /api/v1/desk/hardship/cases` | AG Grid server-side row model. Filter and sort models map to an allowlist of columns (status, level, bucket, tier, SLA state, assignee, opened on). Keyset paging |
| `GET /api/v1/desk/hardship/cases/{caseId}` | Evidence panel: signals with numbers, option previews and diffs, member choice, both narratives with `source`, grounding result, agent run summary, audit entries, SLA clock |
| `POST /api/v1/desk/hardship/cases/{caseId}/decision` | `{decision: APPROVE\|REJECT, reasonCode, note?, evidenceViewedMs}`; role decides whether it is the maker or the checker step |
| `POST /api/v1/desk/hardship/cases/{caseId}/retry` | Retry a failed execution; officer role |
| `POST /api/v1/desk/hardship/kill-switch` | `{scope, enabled, reason}`; admin role; audited |

Example decision request and response:

```json
{ "decision": "APPROVE", "reasonCode": "INCOME_DROP_VERIFIED", "note": "Signals match the member's own message.", "evidenceViewedMs": 41200 }
```
```json
{ "caseId": "9b2e…", "requestStatus": "OPEN", "role": "MAKER", "next": "CHECKER", "auditSeq": 18423 }
```

### 6.3 Agent tools (`contracts/openapi/agent-tools.yaml`, read-only, scoped token)

`get_loan_summary`, `get_repayment_schedule`, `get_loan_aging`, `get_hardship_signals`, `get_income_summary`, `preview_option`, `list_prior_cases`, and the one write-like call `submit_proposal`. Each tool is bound by the token to one `loanId` and one `caseId`. Any other id returns 404. Outputs carry no names, emails, phone numbers or PayPal payer ids. Details, schemas and the proposal shape are in [04-agent-architecture.md](04-agent-architecture.md).

### 6.4 Internal jobs (`contracts/openapi/internal-jobs.yaml`; service token; idempotent)

| Path | Purpose |
| --- | --- |
| `POST /internal/jobs/hardship/scan` | `{businessDate, shardGroup, shardCount, after, limit}` → `{scanned, opened, nextAfter, done}` |
| `POST /internal/jobs/hardship/sla-sweep` | Fires due `case_timer` rows: SLA escalations, offer expiry, hold expiry |
| `POST /internal/jobs/hardship/expire` | Closes stale offers and runs the cool-down bookkeeping |
| `POST /internal/jobs/governance/verify-audit-chain` | Recomputes the hash chain; any break is a Sev-1 alert |

### 6.5 Errors

| `type` code | Status | Meaning |
| --- | --- | --- |
| `hardship-no-open-offer` | 404 | No open case or active offer for this loan (also returned for another member's loan) |
| `hardship-not-eligible` | 422 | Loan not active, in the 60-plus bucket, or already has an open case |
| `hardship-policy-violation` | 422 | The option breaks a v1 cap (details name the cap and the count) |
| `hardship-offer-stale` | 409 | The schedule changed since the offer; a new revision was issued and needs confirming |
| `hardship-case-closed` | 409 | The case already ended |
| `sod-violation` | 403 | The decider is the maker, or is the member |
| `proposal-rejected` | 422 | The validator refused an agent proposal; `checks` lists why |
| `agent-scope-violation` | 403 | The token does not cover this loan or tool |
| `agent-disabled` | 503 | Kill switch on, budget spent or breaker open; hardship continues with templates |
| `idempotency-key-reused` | 409 | Same key, different body |

## 7. Events (outbox → `contracts/events/domain-events.json`)

Every event carries `eventId`, `occurredAt`, `businessDate`, `loanId`, `memberId`, `caseId`, `causationId`, `correlationId`. Ordering is per loan.

| Event | Extra payload | Consumers |
| --- | --- | --- |
| `HardshipCaseOpened` | `origin`, `targetSeq`, `level`, `score`, `policyVersion` | desk-query, Elastic |
| `HardshipSignalDetected` | `runId`, `scanDate`, `level`, `points`, `signals[{code, points}]` | desk-query, Elastic |
| `HardshipOfferIssued` | `offerId`, `revision`, `expiresOn`, `options[{label, primitive, summaryNumbers}]`, `narrativeSource` | engagement, desk-query |
| `HardshipOptionChosen` | `offerId`, `optionLabel`, `tier`, `snapshotHash` | desk-query, governance |
| `HardshipDecisionMade` | `decision`, `tier`, `makerRef`, `checkerRef`, `reasonCode` (staff ids are pseudonymous refs) | desk-query, engagement |
| `HardshipApplied` | `scheduleVersion`, `primitive`, `approvalRef`, `instalmentsOnPlan[seq]` | credit-record, engagement, desk-query, Bryntum SSE |
| `HardshipCaseClosed` | `closedReason`, `closedOn` | credit-record, engagement, desk-query |

Governance adds the generic `ApprovalRequested` and `ApprovalDecided` events (subject type, subject id, tier, decision). Hardship consumes `ApprovalDecided` and republishes the domain-level `HardshipDecisionMade`, so consumers of hardship never depend on governance.

## 8. Concurrency and consistency

- **One open case per loan** is a database rule (partial unique index), not application logic. A second scan or a double `HELP` hits it and becomes a no-op.
- **Per-loan serialisation of the write:** `applyReschedule` takes the F4 loan row lock. The saga does not lock the loan itself.
- **Idempotent saga:** `approvalRef = caseId:optionId:requestId`. Once the required F4 change in section 1.1 is made, `applyReschedule` returns the existing version for a repeated `approvalRef`; the unique index on `schedule_version (loan_id, caused_by)` is the backstop.
- **Stale-preview guard:** the `snapshot_hash` is checked at choose, at approve and at apply. Any mismatch voids approvals and sends the member back to confirm ([03 §8](03-algorithms.md#8-stale-preview-detection)).
- **Audit chain:** appended under an advisory lock inside the business transaction, so an entry exists if and only if the business change committed.
- **Scan:** one transaction per loan, `UNIQUE (loan_id, scan_date)`, and `FOR UPDATE SKIP LOCKED` on the work queue rows, so two workers never double-process a loan.

## 9. Security

| Surface | Control |
| --- | --- |
| Member endpoints | PayPal login session. `case.member_id = principal` is checked in the use case. Mismatch returns 404 |
| Desk | Staff passkeys. `officer` is the maker, `checker` is the checker, `admin` runs the kill switch. A person holding both roles can act in only one role on one request |
| Maker–checker | The database enforces SoD (section 5.2). The UI hides the buttons but the trigger is the control |
| Internal jobs | Short-lived service token, network-restricted to worker and workflow services |
| Agent calls | Token issued per run: claims `{runId, loanId, caseId, scopes[], exp ≤ 10 min}`. Cross-loan reads return 404. The gateway never forwards a member session |
| Agent outputs | Untrusted. They reach a member or a database only after the validator and the grounding check |
| Member free text | Only through the tool-less extractor; closed-set output (`reasonCategory`, `confidence`); the text itself is never passed to another prompt |
| PII | Agents get a pseudonymous member ref, whole-dollar amounts, dates and signal codes. `PiiMasker` redacts at the gateway. Logs hold hashes of prompts, not text |
| Kill switch | Admin button and automatic trip; audited; effect is immediate and drops to templates |

## 10. Observability

- **Trace:** one trace per case from `scan` span to `HardshipApplied`, with spans for each tool call and the LLM call. `caseId`, `loanId` and `runId` are attributes; member names and phone numbers never are. Exported with OTel to Elastic APM.
- **Metrics (Micrometer → Elastic):**
  - `seed_hardship_scan_duration_seconds`, `seed_hardship_scan_loans_total`, `seed_hardship_cases_opened_total{origin,level}`.
  - `seed_hardship_offer_to_choice_seconds`, `seed_hardship_approval_latency_seconds{tier}`, `seed_hardship_sla_breach_total`, `seed_hardship_saga_failed_total`.
  - `seed_hardship_hold_active`, `seed_hardship_stale_offer_total`.
  - `seed_agent_runs_total{agent,outcome}`, `seed_agent_grounding_failures_total`, `seed_agent_proposal_rejection_ratio`, `seed_agent_fallback_total{reason}`, `seed_agent_tokens_total`, `seed_agent_cost_micro_usd_total`, `seed_agent_kill_switch_state`.
  - `seed_governance_audit_chain_verified`, `seed_officer_rubber_stamp_flags_total`.
- **Dashboards (Elastic):** the hardship funnel (detected → offered → viewed → chosen → approved → applied), alert precision as cases resolve, approval latency by tier, agent health (rejection ratio, grounding failures, fallback rate, cost per case), and the roll-rate effect of applied options.
- **Alerts (Zapier → Slack):**

| Alert | Condition | Action |
| --- | --- | --- |
| Proposal rejection ratio | More than 20% over the last 50 runs | **Auto-trip** the agent kill switch; page the admin |
| Grounding failures | More than 3 in one hour | Auto-trip; page the admin |
| Approval SLA | `SLA_50` notify the desk pool; `SLA_80` page the admin; `SLA_BREACH` Sev-2 | Escalate; hold may be extended once |
| Saga failed | Any `APPROVED_EXECUTION_FAILED` | Sev-2, desk dead-letter row |
| Audit chain broken | Any mismatch | Sev-1 |
| Budget | 80% of the daily token or cost cap | Warn; at 100% the gateway refuses new runs |
| Rubber-stamp flag | The detector fires for an officer | Notify the admin; no automatic block |

- **Logs:** structured JSON; `PiiMasker` on every message; agent prompts and outputs are not logged, only their hashes, lengths and the verdicts.

## 11. Failure modes

| Failure | Behaviour |
| --- | --- |
| LLM provider down or times out (20 s cap, 6 tool calls max) | The circuit breaker opens after 5 failures in 60 s. Template copy is used. The case continues. `seed_agent_fallback_total{reason=provider}` counts it |
| OpenRouter returns 429 | Back off with full jitter (base 1 s, cap 30 s), try the next model in the chain, then the template. The day's budget is unaffected by refused calls |
| Model returns invalid JSON | One repair retry at temperature 0. Then fall back to the template |
| Grounding check fails | The narrative is discarded and replaced by the template. The failure is counted. Three in an hour trips the kill switch |
| Validator disagrees with the agent's numbers | `proposal-rejected`; the template is used. The disagreement is logged for the eval set |
| Prompt injection in a member's message | The extractor returns only a category. Nothing from the text reaches a prompt. The injection corpus in CI proves it |
| Officer SLA breach | `SLA_50`, `SLA_80`, `SLA_BREACH` timers escalate. A case whose target instalment is due within 2 days is flagged `PRIORITY` with a 4-business-hour SLA. The hold may be extended once by 2 days, appended as an audited row, never silently |
| Autopay charge lands while a request is pending | It is an ordinary payment. If it clears the target instalment the case becomes `CANCELLED_PAID` and the request is `VOIDED`. The choice confirmation tells the member that a charge scheduled before approval may still go through. Suspending the subscription while a request is pending is a stretch item, not in v1 |
| Saga fails after approval | Retry with backoff (5 attempts). Then `APPROVED_EXECUTION_FAILED`, a desk dead-letter row and an alert. No half-applied schedule exists because `applyReschedule` is atomic |
| Member taps the choice twice | The `choice` idempotency key returns the first result |
| Member pays while approval is pending | `RepaymentReceived` clears the target instalment → `CANCELLED_PAID`; the hold is released; the approval request is `VOIDED`. If the payment is partial, the approval stands and the preview is re-checked |
| Schedule changes between offer and approval (preview stale) | The `snapshot_hash` no longer matches. The offer is `STALE`, a new revision is issued, approvals are voided, and the member re-confirms (`hardship-offer-stale`) |
| Two officers decide at once | `UNIQUE (request_id, role)`; the second gets `409` |
| Maker equals checker | The trigger raises; the API returns `sod-violation` |
| Hold ends with no decision | Only after the one allowed extension. The miss still stands: `InstalmentMissed` is written at the first COB after the hold ends, with the attribution `SLA_BREACH` when the cause was our own delay, which F6 reads as "not the member's miss" |
| Member opted out of SMS | In-app card only; no SMS is queued |
| PayPal outage during execution | `applyReschedule` still commits; the mandate sync retries by F4's rules |
| `HELP` flood from one member | One open case per loan; the rest are no-ops |

## 12. Scale notes

- **Daily scan:** only loans with an instalment due in the next 10 days or in grace are scored. With monthly instalments that is about a third of 100,000 active loans, or roughly 35,000 scorecard evaluations a day. The scorecard is O(13 weeks + n) in memory, and the reads are one schedule and one income series per loan, so DB round trips dominate at about 6 ms per loan. With 8 shard groups a worker handles roughly 4,400 loans per run, about 30 seconds. A `scorecard_run` row is written only when the points are above zero or the level changed; the table is partitioned by month with 13 months kept.
- **Keyset paging:** `after` is the last `loan_id`; each page costs the same (same pattern as F4 close of business).
- **LLM cost shape:** calls happen only for cases above the threshold, plus borderline second opinions. Estimate for 100,000 loans: about 300 new cases a day and about 500 borderline checks, so about 800 calls a day at about 2,000 tokens each, or about 1.6 million tokens a day. The gateway enforces a hard daily cap (default 3 million tokens) and refuses new runs beyond it. These are planning numbers; measure in shadow mode before trusting them.
- **Outreach limits** bound SMS volume: one proactive message per instalment, two per 30 days per member.
- **Desk queue:** the grid reads a `desk-query` projection with an index per filterable column, never the `hardship` tables directly.
- **Audit chain:** appends serialise on one advisory lock. At 18,000 entries a day that is far below the lock's throughput; shard the chain by month if it ever matters.
