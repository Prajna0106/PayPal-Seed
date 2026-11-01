# F5 — Implementation Plan

**Today:** Sun 1 Nov 2026 · **Feature freeze:** Mon 9 Nov · **Submissions close:** Thu 12 Nov 12:00 PT (PayPal's announcement gives 12 Nov; third-party pages agree on 12:00 PT. Confirm on Devpost, see [05 §1.3](05-sponsor-integrations.md#13-confirm-by-hand-before-6-nov)). F4 is built and green (`./gradlew clean build` runs 120 tests). F5 sits on top of it, so most of the schedule engine, the ledger, the PayPal adapter and the reminder pipeline already exist.

The honest picture: there are 8 working days between today and the freeze, and the MUST tier below costs about 10.9 engineer-days at the estimates in this document. The estimates assume one engineer working alone. With an AI pair that writes tests and boilerplate, the team has been running at roughly 1.5 times that speed (F4's plan costed about 11 engineer-days and landed in a few sessions), so the calendar in section 4 fits, but it has little slack. The cut line in section 6 is fixed in advance so that nobody argues about it on the day.

## 1. Prerequisites from other features

F5 cannot finish without these. Where one is late, the stub keeps F5 moving.

| Needs | From | Minimum slice | Stub if late |
| --- | --- | --- | --- |
| `LendingFacade.previewReschedule` and `applyReschedule` | F4 (built) | Both, with `MOVE_DATE`, `DEFER_TO_END`, `REAMORTIZE` | None. Already built. **One change is needed:** `applyReschedule` must be idempotent on `approvalRef` (task T3) |
| F4 events `RepaymentFailed`, `GraceNudgeDue`, `InstalmentDueSoon`, `LateBucketChanged`, `InstalmentMissed`, `ScheduleChanged` | F4 (built) | As published today | None |
| Consented income summary (weekly inflow for 90 days, usual income day) | F3 | `IncomeSummaryProvider` port returning banded weekly inflow and the income-timing profile | `FixtureIncomeSummary`: a seeded series per member from the Simulator cohort. The demo is built on it either way, so nothing is lost |
| Two-person approval, proposal, verdict, audit chain | F7 `governance` | A minimal slice built inside F5 (task T5): proposal, verdict via a registered `ProposalValidator`, approval rows, hash chain | This is the stub. Do not wait for the full F7 |
| "On plan" marker for the credit record | F6 | The `HardshipApplied` event with the case id and `instalmentsOnPlan` | Event only. A logging consumer stands in for F6 |
| Savings behaviour for signal S6 | F1 | Streak paused, balance under one instalment | Fixture field in the income summary; S6 is worth 1 point and can be switched off |
| Reminder pipeline, quiet hours, opt-out, Zapier outbound and inbound | F4 `engagement` (built) | New templates and reply keywords | None |
| Member session, staff roles (`officer`, `checker`, `lead`) | identity | Roles as headers | Dev-header auth that F4 already uses |
| Member and desk front ends (Next.js) | Separate work | See section 5 | The Postman mock server and the Postman collection |
| LLM access (OpenRouter key, credits), Astropods access | External | A key in the `llm` environment group | `FakeLlmClient` and templates. The whole safety net runs without a key |

## 2. Spikes first

Each spike has a **30-minute budget**. Write the answer in `docs/features/f5-hardship-safety-net/spikes.md` and commit. Ordered by risk, highest first. If a spike is not answered in its budget, take the fallback and move on.

| Spike | Question | Fallback if it fails | Blocks |
| --- | --- | --- | --- |
| **SH4** Devpost rules | What are the exact closing time, required deliverables, video length and hosting, repository licence rules, judging weights, credit codes? (The pages block automated reads. Read them in a browser.) | Work to the plan in [05 §1.3](05-sponsor-integrations.md#13-confirm-by-hand-before-6-nov); re-plan the same day if anything differs | Everything (deadline) |
| **SH3** OpenRouter flags | Are the provider preference `data_collection: deny`, zero data retention, the `models` fallback array, JSON-schema structured output and per-call cost reporting available for the models we want? | Call one provider directly through the same `LlmClient` port; templates stay the safety net | T8 |
| **SH1** Astropods | What is the blueprint format? Can a Java-backed agent run there? What are the runtime limits, the eval harness and the credits? | Run the Guardian in the `worker` role and the evals in JUnit; say so in the submission | T8, T11 |
| **SH6** AG Grid licence | Does the hackathon licence cover the enterprise server-side row model and AG Studio? | Client-side row model over a paged API | T10 and the desk app |
| **SH7** Bryntum licence | Is a Scheduler Pro trial available for the hackathon? Does the drag event expose the new date before drop? | Two stacked before and after lists from the preview diff | The member and desk apps |
| **SH5** Zapier SMS | Latency and reliability of the two-way SMS Zap; can a reply carry the sender's number and text? | In-app card and a logged SMS body | T9 |
| **SH2** PayPal MCP | The real MCP tool names, the sandbox access-token flow, whether it can list sandbox subscriptions | Skip the MCP demo beat; the PayPal use does not depend on it | The optional demo beat only |

Total about 3.5 hours, split across Sun 1 and Mon 2 Nov. Do SH4 today.

## 3. Tasks (in build order)

Estimates are engineer-days without the AI pair. Each task ends with its tests green and a commit.

| # | Task | Done when | Est. | Tier | Depends on |
| --- | --- | --- | --- | --- | --- |
| T0 | Spikes SH1 to SH7 (section 2) | Answers committed; fallbacks chosen | 0.5 | MUST | none |
| T1 | Contracts: member, desk and internal-job paths in `seed-api.yaml` and `internal-jobs.yaml`; `agent-tools.yaml` tools and `submit_proposal`; hardship events in `domain-events.json`; Postman collection skeleton; mock server up | `postman collection lint` passes; the mock returns the Ana examples (LIKELY_MISS, quiet loan, borderline); the member app can build against it | 0.5 | MUST | T0 |
| T2 | `engines/rules`: `HardshipPolicy` v1, scorecard with reason codes and evidence, hysteresis, `OptionRanker`, `ApprovalTier`, grounding-check normaliser | PROP-H-01 to PROP-H-11, TC-HSG-01 to TC-HSG-16, TC-HSG-30 to TC-HSG-32, TC-HOP-01 to TC-HOP-16, TC-HOP-20, TC-AGT-02 to TC-AGT-04; ArchUnit TC-ARC-H3 | 1.25 | MUST | none (pure) |
| **T3** | **F4 changes in `lending` (additive), part a:** (1) `applyReschedule` idempotent on `approvalRef`: today `RescheduleService.apply` always inserts a new schedule version, so a replay would create a second one; add a lookup on `caused_by = approval:<ref>` (with a unique index) and return the existing view; (2) migration V3 with `loan_hardship_flag`; (3) `LendingFacade.setHardshipOpen` (`MANDATORY` propagation); (4) retry planning in `CollectionService` and the nudge triggers in `MemberMessageTriggers` skip flagged loans (F4 AC3); regenerate jOOQ | TC-HEX-02, TC-HEX-18, TC-HCS-46; the F4 suite still green | 0.4 | MUST | none |
| T3b | **F4 changes, part b (grace hold):** migration V3 `loan_hold` with the partial unique index and the `until` CHECK; `placeHold` and `releaseHold`; `LoanRecalculator` skips `recordMissed` while a hold covers the instalment and the `GRACE_EXPIRY` timer is re-planned; `MandateReconciliation.desiredMandate` returns `SUSPENDED` while a hold is active | TC-HCS-30 to TC-HCS-39, TC-HCS-50 to TC-HCS-52; CobDifferentialIT still equals the oracle | 0.6 | SHOULD | T3 |
| T4 | `hardship` module: migrations and jOOQ repositories (case, signal, offer, option snapshot, choice, case timer); signal ingestion from F4 events; scan job endpoints (start, shard, finish) with keyset paging and the 10-day window; case lifecycle state machine with durable timers; options through `previewReschedule`; policy evaluated at decision and apply time | TC-HSG-17 to TC-HSG-29, TC-HSG-33, TC-HOP-17 to TC-HOP-24, TC-HCS-01 to TC-HCS-25, PROP-H-07 | 1.5 | MUST | T2, T3 |
| T5 | `governance` minimal slice: proposal intake, `ProposalValidator` registry (hardship registers its own), verdict, approval with a single maker, decision idempotency, SLA timers and the sweep job | TC-HAP-01, TC-HAP-02, TC-HAP-05 to TC-HAP-09, TC-HAP-11, TC-HAP-14 to TC-HAP-17, TC-HAP-21 to TC-HAP-26 | 0.6 | MUST | T1, T4 |
| T5b | Checker and separation of duties (database constraint), reversal as a new decision, hash-chained `audit_entry` with append-only triggers and the nightly verify job, rubber-stamp detector | TC-HAP-03, TC-HAP-04, TC-HAP-10, TC-HAP-12, TC-HAP-18 to TC-HAP-20, TC-HAP-27 to TC-HAP-29 | 0.6 | SHOULD (checker is MUST for tiers B and C if time allows; see section 6) | T5 |
| T6 | Execution saga: apply through `LendingFacade`, mandate sync (F4 reconciler), retries and nudges cancelled, "on plan" event, confirmation message, case closed, retry with backoff on failure | TC-HEX-01 to TC-HEX-16, TC-HEX-17, CHAOS-H8, CHAOS-H9, CHAOS-H12 | 0.75 | MUST | T3, T5 |
| T7 | `agent-gateway` (part a): the seven read tools, scoped run tokens (one loan, one case, 10 minutes), `POST /agent/v1/proposals` with idempotency, the tool-less reason extractor endpoint, `agent_run` recording | TC-SEC-01 to TC-SEC-08, TC-HAPI-16, TC-ARC-H2, TC-AGT-22 | 0.75 | MUST | T1, T4 |
| T7b | `agent-gateway` (part b): kill switch (flag and desk endpoint), daily budget, per-case rate limit, circuit breaker per model, auto-trip rule | TC-AGT-14 to TC-AGT-21, TC-SEC-09, CHAOS-H4, CHAOS-H5 | 0.5 | SHOULD, **protected** (never dropped before T9 and T10) | T7 |
| T8 | Guardian: `LlmClient` port, OpenRouter adapter, `FakeLlmClient`; prompts for `BRIEF` and `OUTREACH`; runtime grounding check; deterministic templates (en); Astropods blueprint or `worker` fallback | TC-AGT-01 to TC-AGT-04, TC-AGT-09 to TC-AGT-13, TC-AGT-25, CHAOS-H1 to CHAOS-H3, E2E-H-02 | 1.0 | MUST | T2, T7, SH1, SH3 |
| T8b | Guardian `SECOND_OPINION` mode (escalate-only) and Spanish templates | TC-AGT-05 to TC-AGT-08, TC-AGT-26, TC-AGT-27 | 0.5 | SHOULD | T8 |
| T9 | Outreach and consent: offer templates, due-date-minus-1 reminder, quiet hours, limits, Zapier reply keywords (`A`, `B`, `C`, `D`, `NO`, `HELP`), member endpoints (read offer, ask for help, choose, decline) | TC-HCS-40 to TC-HCS-49, TC-HAPI-02 to TC-HAPI-05, TC-HAPI-09, TC-HAPI-10, TC-HAPI-14, TC-HAPI-15 | 0.75 | MUST | T4, T8 |
| T10 | `desk-query` hardship projection and endpoints: SSRM queue with column allowlist and SLA sort, case detail (evidence, previews, narrative, source numbers), decision | TC-HAPI-06, TC-HAPI-07, TC-HAPI-11, PERF-H4 | 0.75 | MUST | T5 |
| T11 | Injection corpus (at least 60 cases), local eval runner over recorded outputs, shadow mode flag and comparison, `agent_run` metrics | TC-INJ-01 to TC-INJ-14, TC-AGT-23, TC-AGT-24, TC-AGT-28, E2E-H-03 | 0.75 | SHOULD (a 20-case smoke corpus is MUST) | T8 |
| T12 | Postman: collection folders, request tests, mock server, injection data run, CI gate; monitor and Simulator cohort | TC-HAPI-01, TC-CON in CI; section 15 of [06-test-plan.md](06-test-plan.md) | 0.75 | MUST (collection, mock, CI gate); SHOULD (monitor, chaos folder, Simulator cohort) | T1 and each endpoint |
| T13 | Elastic: `seed-agent-runs` and `seed-hardship-events` indexes, the three ES\|QL rules, kill-switch alert to Zapier, one APM trace | One trace from scan to apply; the rules return the seeded bad run | 0.5 | SHOULD | T7, T8 |
| T14 | Render: `workflows/hardship-scan` (start, shard fan-out, finish, SLA sweep, expiry), blueprint env group `llm`, Key Value budgets; **fallback Spring scheduler in the `worker` role** | The run tree is green in the preview environment; the scheduler fallback is tested | 0.5 (scheduler fallback 0.25 is MUST) | SHOULD | T4, T5 |
| T15 | Fairness and calibration: feature-name scan, tool-schema scan, counterfactual and parity tests on the Simulator cohort, backtest with precision, recall and lead time | TC-FAIR-01 to TC-FAIR-11, TC-HSG-12, TC-HSG-14 | 0.5 | SHOULD (TC-FAIR-01, 02, 06, 07 are MUST: cheap) | T2, T12 |
| T16 | Demo and end to end: demo seed (Ana and cohort), sandbox run, E2E-H-01 and E2E-H-02 twice in a row, then E2E-H-03 to E2E-H-05, video recording | E2E-H-01 and E2E-H-02 pass twice in a row; the video script in [05 §12](05-sponsor-integrations.md#12-demo-script-under-3-minutes) is rehearsed | 0.75 | MUST (E2E-H-01, E2E-H-02, the video); SHOULD (the rest) | T6, T9, T10 |
| T17 | Documentation and submission: README quick start, architecture picture, test evidence, sponsor statements, Devpost text, public repository check | The submission checklist in [05 §13](05-sponsor-integrations.md#13-submission-checklist) is fully ticked | 0.75 | MUST | all |
| T18 | Stretch: Seed MCP server over `agent-gateway`; policy-handbook retrieval with Elastic; fairness dashboard; shadow challenger model | Demonstrable in one minute | 1.0 | STRETCH | T7, T13 |

**MUST total:** about 10.9 engineer-days (T0 0.5, T1 0.5, T2 1.25, T3 0.4, T4 1.5, T5 0.6, T6 0.75, T7 0.75, T8 1.0, T9 0.75, T10 0.75, T12 core 0.4, T14 fallback 0.25, T16 0.75, T17 0.75). **SHOULD:** about 4.6 days. **STRETCH:** 1.0 day. See section 4 for how this maps to the calendar and section 6 for what goes first.

## 4. Critical path and calendar

```text
Spikes:      T0 (SH4 first) ─────────────────────────────────────────────┐
Contracts:   T1 ─────────────→ T12 (mock, collection, CI gate)           │
Pure engine: T2 ──┐                                                      │
F4 changes:  T3 ──┴→ T4 ──→ T5 ──→ T6 ──┐                               │
                     │       └──→ T10 ───┼──→ T16 (E2E, video) ──→ T17 ──┘
                     ├──→ T7 ──→ T8 ──→ T9 ─┘
                     │        └→ T7b            T3b, T5b, T8b, T11, T13, T14, T15: SHOULD, off the critical path
```

The critical path is T2 → T4 → T5 → T6 → T16 → T17, with T7 → T8 → T9 beside it. T3b, T5b, T8b, T11, T13, T14 and T15 are valuable but off the critical path.

| Date | Milestone |
| --- | --- |
| **Sun 1 Nov (today)** | Plan and docs. SH4 answered by hand. Claim any credits found on the resources page. Start T0 |
| **Mon 2 Nov** | T0 finished (SH3, SH1, SH6, SH7, SH5, SH2). T1 contracts and mock server up. T2 started: scorecard and policy with property tests |
| **Tue 3 Nov** | T2 finished (ranker, tiers, grounding normaliser). T3 done: `applyReschedule` idempotency, flag, V3 migration, jOOQ regenerated |
| **Wed 4 Nov** | T4: `hardship` schema, signal ingestion, scan endpoints, case state machine. Postman collection begins to run beside each endpoint (T12). Desk queue projection stubbed |
| **Thu 5 Nov** | T4 finished (options, snapshots, timers). T9: offer templates, choice endpoint, Zapier keywords. T5: proposal, verdict, single-maker approval |
| **Fri 6 Nov** | T5 finished. T6: saga, apply through lending, "on plan" event. T7: tools, run tokens, proposal intake. T10: queue, detail, decision endpoints. **Front end: member timeline with ghosts (F4 payload)** |
| **Sat 7 Nov** | T8: Guardian with `FakeLlmClient` then OpenRouter; grounding check; template fallback. T7b: kill switch. T12: collection, injection data run, CI gate. **Front end: option cards, desk queue and detail** |
| **Sun 8 Nov** | T16: demo seed, E2E-H-01 and E2E-H-02. SHOULD items in this order: T5b checker and audit chain, T3b hold, T11 injection corpus and shadow mode, T14 Render workflow, T13 Elastic. **Front end: kill switch toggle, dashboard** |
| **Mon 9 Nov** | **FREEZE.** E2E-H-01 and E2E-H-02 pass twice in a row. Submission checklist drafted. Record the first take of the video. Only fixes after this |
| Tue 10 Nov | Fixes only. Re-record the video. Write the Devpost text. Full `./gradlew clean build` and the Postman suite in the preview environment |
| Wed 11 Nov | Re-run the submission checklist and the rules check (SH4) against the Devpost pages. Upload the video. **Submit** if everything is ticked |
| Thu 12 Nov | Buffer. If not yet submitted, submit by 09:00 PT, three hours before the 12:00 PT close. No code changes |

## 5. What the front end needs from the backend, and by when

The member and desk apps are separate work. The Postman mock server (T1) lets the front end start on Mon 2 Nov.

| Screen | App | Backend ready | Needs |
| --- | --- | --- | --- |
| Member timeline with ghost events | `web/apps/member` | Already built (F4) | The existing timeline payload |
| Option cards with exact numbers, consent, "no change" choice | `web/apps/member` | Fri 6 Nov (T4, T9) | `GET /api/v1/loans/{id}/hardship`, `POST .../cases/{id}/choice`, `POST .../cases/{id}/decline`, `POST /api/v1/hardship/cases` (ask for help), the preview endpoint |
| Hardship queue (AG Grid SSRM) | `web/apps/desk` | Fri 6 Nov (T10) | `GET /api/v1/desk/hardship/cases` with the column allowlist |
| Case detail with evidence, narrative and source numbers | `web/apps/desk` | Sat 7 Nov (T10) | `GET /api/v1/desk/hardship/cases/{id}` |
| Approve and reject with reason code; maker and checker banner; audit hash | `web/apps/desk` | Sat 7 Nov (T5, T5b) | `POST /api/v1/desk/hardship/cases/{id}/decision` |
| Kill switch toggle and agent run panel | `web/apps/desk` | Sun 8 Nov (T7b) | Kill-switch endpoint and the `agent_run` summary |
| Elastic dashboard | Elastic | Sun 8 Nov (T13) | Indexed events |

If a backend task slips by one day, the front end keeps working against the mock. If the screens slip past Sat 7 Nov, record the demo with the Postman Flows and the API responses on screen, and say so.

## 6. Cut line

Drop in this order if behind. Re-check on **Wed 4 Nov**, **Fri 6 Nov** and **Sun 8 Nov**.

1. **STRETCH (T18)**: Seed MCP server, policy retrieval, fairness dashboard, challenger model.
2. **Elastic (T13)**: keep `agent_run` in Postgres and show the same three queries as SQL on the desk.
3. **Render Workflow (T14)**: use the Spring scheduler in the `worker` role (this fallback is MUST).
4. **Bryntum drag constraint and Simulator monitor/chaos folder**: keep the ghost timeline and the preview diff.
5. **Fairness parity and counterfactual tests (TC-FAIR-03, TC-FAIR-04)**: keep the feature-name scan and the standing tests.
6. **Injection corpus breadth (T11)**: shrink from 60 to a 20-case smoke corpus. Never remove the grounding check or the kill switch.
7. **Checker (T5b)**: tiers B and C run with a single maker and a `checkerRequired` policy flag set to false. Say so in the submission and in the video ("a second approver is built into the policy and switched on in the full design"). Do this only if the single-maker path is already green.
8. **Grace hold (T3b)**: members who ask for help before grace expires are unaffected. Document that the hold is designed but not built.
9. **AI second opinion and Spanish templates (T8b)**.

**Never cut:** the scorecard and policy (T2); the case lifecycle with the member's recorded choice (T4); officer approval (T5); apply through lending with the idempotent `approvalRef` (T3, T6); the template fallback and the runtime grounding check (T8); the "agent has no write path" boundary (T7); E2E-H-01 and E2E-H-02; the public repository, the README and the video (T16, T17). These carry the claim that the safety net is real, bounded and works with the AI off.

## 7. Plan "done means" for F5, mapped

| Feature statement | Proven by |
| --- | --- |
| "AI spots a likely missed payment early" | The scorecard raises LIKELY_MISS at least 5 days before the due date on the synthetic cohort (TC-HSG-12); E2E-H-01. Be exact in the video: code detects, AI adds a second opinion and writes the words |
| "...and offers a pause or a smaller, longer plan" | Options A to D with exact previews (TC-HOP-*, PROP-H-08); the option cards and the SMS |
| "...a human approves" | TC-HAP-*, TC-HEX-*, E2E-H-01; no schedule change without the member's choice and the required approval (HINV-1) |
| "...and the member stays on plan" | TC-HEX-13 to TC-HEX-15, TC-FAIR-06 |
| "...and it is safe with AI" | TC-AGT-*, TC-INJ-*, TC-SEC-*, CHAOS-H1 to CHAOS-H5, E2E-H-02, E2E-H-03 |

## 8. Risks

| Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- |
| Time: the MUST tier is about 10.9 engineer-days against 8 working days before the freeze | High | High | The AI pair, the fixed cut line in section 6, the Postman mock so the front end is not blocked, and no work on STRETCH until the freeze checks pass |
| Devpost rules differ from the summaries (video length, licence, closing time) | Medium | High | SH4 today; re-check on Wed 11 Nov; submit a day early |
| F4's `applyReschedule` replays create a second schedule version (found in code review) | Certain without the fix | High | T3 is the first F4 change and has TC-HEX-02 and a property test with replays (TC-HEX-16) |
| The hold changes close of business and could break the F4 oracle test | Low | High | T3b is SHOULD and behind the cut line; CobDifferentialIT runs in the same build; the hold only suppresses `recordMissed` |
| OpenRouter or the chosen model misbehaves (latency, schema support, data-collection flag) | Medium | Medium | SH3; `FakeLlmClient` for all tests; templates always available; a direct-provider fallback behind the same port |
| Astropods does not fit a Java backend (SH1) | Medium | Medium | The Guardian is a plain class behind ports; run it in the `worker` role |
| The AI says something wrong in the live demo | Low | High | Runtime grounding check; templates; pre-recorded run for the video |
| AG Grid or Bryntum licence unavailable (SH6, SH7) | Medium | Medium | Client-side row model; stacked before and after lists |
| The front end falls behind the backend | Medium | High | Screens in section 5 with dates; mock server from Mon 2 Nov; fall back to API-driven recording |
| Scope creep into F6, F7 or F9 | Medium | Medium | F7 is a minimal slice only; F6 is an event only; F9 Coach is out of scope |
| Synthetic-data claims are over-stated in the submission | Medium | High | Every metric is labelled synthetic; regulation is referenced as a design input, not legal advice |
| One engineer is unavailable for a day | Medium | High | Keep the plan's calendar slack on Wed 11 Nov and Thu 12 Nov; the cut line is already ordered |

## 9. Definition of done

- [ ] All MUST tasks are complete and every MUST test id in [06-test-plan.md](06-test-plan.md) section 16 passes in `./gradlew clean build`.
- [ ] The F4 suite is still green: 120 tests plus the new F5 tests, including `CobDifferentialIT` in both TIMERS and SCAN modes.
- [ ] E2E-H-01 (Ana pauses) and E2E-H-02 (AI off) pass twice in a row in the preview environment against the PayPal Sandbox.
- [ ] The Postman collection runs green in CI and the injection smoke corpus shows zero leaks.
- [ ] The agent has no write path: TC-ARC-H2 and TC-SEC-02 pass.
- [ ] Every number in every message comes from the engine; the grounding check and the template fallback are proven by CHAOS-H3.
- [ ] The audit chain verifies (or, if T5b was cut, the submission says the checker and chain are designed but not built).
- [ ] Docs are up to date: README quick start, the "not built" list, the architecture picture, the sponsor statements.
- [ ] The video is under three minutes (confirmed limit), public, and shows the AI-off path.
- [ ] The Devpost submission is complete and filed before the close (target Wed 11 Nov, latest Thu 12 Nov 09:00 PT).
