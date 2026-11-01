# F5 — Requirements

Scope: noticing early that a member is likely to miss a payment, offering the hardship options, collecting the member's choice, getting the required human approval, applying the change to the schedule, and keeping the member's standing intact. F5 uses the F4 reschedule primitives and the F4 facts. It does not compute the credit record (F6), reserve cover (F8) or the conversational Coach (F9).

## 1. Policy values (versioned in the rules engine)

Every value below is stored in a `HardshipPolicy` record with a `policyVersion`. A case keeps the policy version it was opened under. Values marked "target" are starting points to be checked on the synthetic cohorts in [06-test-plan.md](06-test-plan.md); they are not claims about real members.

| Key | Value | Notes |
| --- | --- | --- |
| `optionA.moveDate` | 1 to 14 days later | The instalment must still be owed. The new date must be after today and before the next instalment. Max 2 per loan per 6 months |
| `optionB.pause` | One month, to the end of the term | Max 1 per rolling 6 months and 2 per loan |
| `optionC.restructure` | Extend 1 to 6 months, instalment floor $25 | Max 1 per loan |
| `optionD.payWhatYouCan` | An ordinary payment (F4 FR-05) | No approval, no reschedule |
| `approval.A` | Member consent, then automatic approval by policy | The approval row records the actor `POLICY` and the policy version |
| `approval.B`, `approval.C` | Officer (maker) plus a different officer (checker) | Reason code required |
| `eligibility` | Loan `ACTIVE`, not `DPD_60_PLUS`, no open case | 60+ days is staff-led and goes to the reserve flow (F8) |
| `scanWindow` | Instalments due in the next 10 days, or in grace | The daily scan and the F4 events both feed it |
| `levels` | `NONE`, `WATCH` (points ≥ 3), `LIKELY_MISS` (≥ 6), `IMMINENT` (≥ 9) | Points and signals in [03](03-algorithms.md) |
| `coolDownDays` | 14 after a case closes | Proactive outreach only; a member's own `HELP` is never blocked |
| `outreach.perInstalment` | 1 proactive message | Plus a reminder if the case is still unanswered at due date − 1 |
| `outreach.per30Days` | 2 | Counted per member |
| `quietHours` | 20:00 to 08:00 member local time | Same as F4. `STOP` is honoured |
| `offerExpiry` | Due date − 1 day | Then the case moves to `EXPIRED` unless the member has chosen |
| `approvalSlaBusinessDays` | 1 | Escalate at 50% of the SLA, page at 80% |
| `holdDays` | 3 | Longest grace hold. See [ADR 0011](../../adr/0011-hardship-case-lifecycle-consent-hold-and-standing.md) |
| `agent.maxToolCalls` | 6 per run | |
| `agent.maxOutputTokens` | 4,000 per run | |
| `agent.timeoutSeconds` | 20 per model call | |
| `agent.tokenTtlMinutes` | 10 | A run token is scoped to one loan and one case |
| `agent.killSwitchTrip` | Rejection rate above 20% over 50 runs, or 3 grounding failures in 1 hour | Falls back to templates |
| `agent.logRetentionDays` | 30 | Prompts and outputs, PII masked |
| Fees on any option | Always 0 | An invariant (F4 INV-5), not a setting |

## 2. Functional requirements

Each requirement lists acceptance criteria (AC). Test IDs map in [06-test-plan.md](06-test-plan.md).

### HFR-01 Score the risk of a missed payment
The pure scorecard turns the member's own consented data into a level with reason codes.
- AC1: Inputs are F4 events and facts, the F3 consented income summary, F1 savings behaviour and the member's own `HELP`. No other data is read.
- AC2: The output is `{level, points, reasons[]}`, and each reason is `{code, evidence}` where `evidence` holds the numbers that fired it.
- AC3: The scorecard is monotonic: adding a risk signal never lowers the level, and removing one never raises it (PROP-H-01).
- AC4: The same inputs and policy version always give the same output, and a call takes at most 1 ms for one loan.
- AC5: A `HELP` request goes straight to `IMMINENT`, whatever the score.

### HFR-02 Raise the alert early enough to help
- AC1: The daily scan evaluates every active loan with an instalment due in the next 10 days or in grace, in shards, and is idempotent for a business date.
- AC2: F4 events (`RepaymentFailed`, `GraceNudgeDue`, `InstalmentDueSoon`, `LateBucketChanged`) re-evaluate the one loan immediately.
- AC3: On the synthetic cohorts, at least 70% of the instalments that the simulator marks "would miss" raise `LIKELY_MISS` or higher at least 5 days before the due date (target, [06](06-test-plan.md) TC-HSG-12).

### HFR-03 Keep alerts few and useful
- AC1: At most one open case per loan. A second trigger updates the open case; it never opens another.
- AC2: A case has the dedupe key `loan:<id>:v<ver>:s<seq>`. Evaluating twice creates one case.
- AC3: Hysteresis: a level drops only after two consecutive scans below its threshold, so one good day does not close an alert.
- AC4: Suppression: no new case while money for the loan is `PENDING` at PayPal (F4 AC5), while a case is open, during the 14-day cool-down, or when the instalment is already paid.
- AC5: On the synthetic baseline, proactive cases stay at or below 10 per 100 active loans per month (target).

### HFR-04 Let the member ask for help
- AC1: An SMS `HELP` (through the F4 inbound endpoint) or the in-app button opens a case at `OFFER_VIEWED` with level `IMMINENT`.
- AC2: It works for any active loan, including one in grace, even during the cool-down.
- AC3: A member who asks for help receives the options in the same session and never has to explain why in free text. A reason is optional.

### HFR-05 AI second opinion (escalate-only)
- AC1: The Guardian reviews a loan at `WATCH` (a signal only; cases open at `LIKELY_MISS` or above) or a loan within 1 point of the next threshold, using only tool output.
- AC2: It may raise the level by at most one step, and each escalation must cite at least one signal code that is present in the tool data.
- AC3: It can never lower a level, never change a case state, and never create or imply an approval.
- AC4: The validator rejects an escalation that cites a signal not in the data. The level then stays at the scorecard's value.
- AC5: With the AI off, the scorecard level stands and the flow continues.

### HFR-06 Generate the options with exact numbers
- AC1: For an eligible loan the system builds the eligible subset of options A, B, C and D. A, B and C come from `LendingFacade.previewReschedule` (no writes); D is a payment preview.
- AC2: Each option shows the new due dates, the new instalment amounts, the total delay in days, the new end date and the total repaid (always equal to the balance).
- AC3: Exactly one option is marked `recommended`, chosen by code (`OptionRanker`). The AI never picks.
- AC4: Each preview is stored as an `option_snapshot` with a hash of the schedule version and the preview. This is the object the member chooses and the officer approves.
- AC5: Every previewed schedule keeps Σ instalments = principal and leaves the outstanding balance unchanged when the money history is re-folded (F4 FR-19, PROP-RSC-01).

### HFR-07 Enforce the policy caps
- AC1: An option beyond its cap is not offered, and a direct request returns `422 hardship-option-not-eligible` with the reason.
- AC2: A pause on a loan with a prior pause inside 6 months is not offered.
- AC3: A restructure that would make an instalment under $25 is not offered, and one that does not reduce the instalment is not offered.
- AC4: Policy is evaluated at decision time and again at apply time. A change in between blocks the apply and reopens the offer.

### HFR-08 Take in and validate a proposal
- AC1: The agent submits `HardshipProposal` through `POST /agent/v1/proposals` with `Idempotency-Key = runId`. The same key returns the same result; the same key with a different body returns `409`.
- AC2: The validator recomputes risk, options and all numbers from source data and compares them with the proposal.
- AC3: The grounding check rejects any amount, date or option name in the narrative that is not in the tool results, after normalising forms such as `$150`, `150 dollars` and `1,200`.
- AC4: The verdict is `APPROVABLE` or `REJECTED(reasons[])`. A rejected proposal falls back to the template outreach and is counted for the kill-switch rule.
- AC5: The proposal and verdict are stored with the run id, prompt hash, model and cost.

### HFR-09 Reach out to the member
- AC1: Channels are SMS through Zapier and an in-app card. SMS goes only to members with a phone number who have not opted out. Otherwise the in-app card is used.
- AC2: Messages are in English or Spanish, at about grade-6 reading level, and show all eligible options together with the exact numbers and a visible "do nothing, I will pay on my own" choice.
- AC3: Messages respect quiet hours (20:00 to 08:00 local), the limit of 1 per instalment and 2 per 30 days, and `STOP`.
- AC4: When a proposal is `APPROVABLE`, the narrative from the agent is used. Otherwise the deterministic template is used. Both show the same numbers.
- AC5: No message includes a countdown, a fee, a penalty threat or a link not generated by our code.

### HFR-10 Record the member's choice
- AC1: A choice (`A`, `B`, `C`, `D`, `DECLINE`) is stored with the option snapshot hash, the channel, a timestamp and the policy version. That record is the consent.
- AC2: A choice made against a stale snapshot (the schedule version changed since) is refused with `409 hardship-offer-stale` and a fresh offer is sent.
- AC3: Choosing `DECLINE` or doing nothing never changes the schedule and never affects standing.
- AC4: The member can change a choice until it is approved. Only the latest choice counts.

### HFR-11 Approve with a human
- AC1: Option A needs the member's choice and then an automatic approval by policy. B and C need a maker and a different checker.
- AC2: The checker is not the maker and not the member. The database enforces it.
- AC3: Each decision needs a reason code from a closed list and an optional note. An officer sees the evidence panel before the buttons are enabled.
- AC4: A decision made against a different schedule version than the one in the snapshot is refused (`409`), and the case returns to a fresh offer.
- AC5: Approvals are append-only. A reversal is a new decision, not an edit.
- AC6: The approval SLA is 1 business day. The case is escalated at 50% and paged at 80% of the SLA.

### HFR-12 Apply the approved option
- AC1: Applying calls `LendingFacade.applyReschedule(request, approvalRef)` with `approvalRef = caseId + optionId + decisionId`. It is idempotent on that reference.
- AC2: On success lending writes a new schedule version, and F4 already revises or suspends the PayPal subscription, rebuilds timers and cancels pending retries.
- AC3: The case is marked "on plan" for the credit record and the member is notified by SMS or in-app.
- AC4: If the apply fails, the case is `APPROVED_EXECUTION_FAILED` and is retried with backoff. No partial state exists, because the reschedule is one transaction.
- AC5: At most one `APPLIED` outcome exists per (case, option). Replaying the apply returns the same result.
- AC6: Afterwards Σ instalments = principal, and the outstanding balance, overpayment and money paid in advance are unchanged.

### HFR-13 Hold the missed-payment fact while a choice awaits approval
- AC1: When a member has chosen an option that needs approval and grace would expire in 3 days or less, hardship asks lending to place a hold on that instalment.
- AC2: A hold stops `InstalmentMissed` from being recorded. Days past due and the bucket are still computed from dates, so the data stays honest.
- AC3: A hold lasts at most `holdDays` (3), is one row per (loan, instalment, case), and is never extended.
- AC4: A hold ends when the option is applied, the officer rejects, the member declines, the case expires, or `holdDays` pass. If the miss still stands, it is recorded on the first close of business after the hold ends, and the timeline shows the hold.
- AC5: Holds are append-only facts owned by lending.

### HFR-14 Run the case lifecycle
- AC1: States and transitions follow [02 §3](02-design.md): `DETECTED`, `OUTREACH_SENT`, `OFFER_VIEWED`, `OPTION_CHOSEN`, `PENDING_APPROVAL`, `APPROVED`, `APPLIED`, and the terminal `DECLINED_BY_MEMBER`, `REJECTED_BY_OFFICER`, `EXPIRED`, `CANCELLED_PAID`, `SUPERSEDED`, plus the retryable `APPROVED_EXECUTION_FAILED`.
- AC2: A case moves to `CANCELLED_PAID` when the member pays enough to be current without help. The hold, if any, ends.
- AC3: Timers (offer expiry, approval SLA, hold expiry) are durable and fire once, like the F4 timers.
- AC4: An invalid transition is rejected and logged; the table of allowed transitions is the only definition.

### HFR-15 Give officers a fast, honest desk
- AC1: `GET /api/v1/desk/hardship/cases` is an AG Grid server-side row model endpoint with a column allowlist, sorted by SLA by default.
- AC2: The case view shows reasons with their evidence numbers beside the narrative, the option previews, the policy checks and the member's choice.
- AC3: The desk records the time between opening a case and deciding. Repeated decisions under 10 seconds raise a "possible rubber-stamp" flag for the team lead.
- AC4: The desk shows the agreement rate between officers and the recommended option, as a quality metric and never as a target.

### HFR-16 Agent read tools are scoped and read-only
- AC1: The tools are `get_loan_summary`, `get_repayment_schedule`, `get_loan_aging`, `get_hardship_signals`, `get_income_summary`, `preview_option` and `list_prior_cases`. All are read-only.
- AC2: Each call needs a run token scoped to one loan and one case, with scopes and an expiry of at most 10 minutes. A call for any other loan returns `404`.
- AC3: Results contain pseudonymous refs and whole-dollar amounts. They contain no names, emails, phone numbers or PayPal payer ids.
- AC4: `get_income_summary` returns banded, consented summaries, not raw transactions.

### HFR-17 Treat member text as untrusted
- AC1: Free text from a member goes only to a tool-less extractor that returns JSON with `reasonCategory` from a fixed list and `confidence`.
- AC2: The extracted category is stored. The raw text is stored with the case for the officer and is never placed in any other prompt.
- AC3: Officer notes are never fed back to an agent.
- AC4: An injection test corpus runs in the evals and must produce no behaviour change (TC-INJ-*).

### HFR-18 Degrade safely and stop when needed
- AC1: A per-model circuit breaker, a daily token and cost budget and a rate limit per case bound the agent.
- AC2: A kill switch (a config flag and a desk button) sends every outreach to templates within 2 seconds.
- AC3: The kill switch trips automatically under the rule in §1, and the trip is logged and alerted.
- AC4: With the model unreachable, scans, cases, offers, approvals and applies all still work.

### HFR-19 Run the agent in shadow first
- AC1: In shadow mode proposals are stored and compared with the deterministic baseline but are not shown to members or officers.
- AC2: Every agent run is stored in `agent_run` with prompt hash, model, tool calls, tokens, cost, latency and verdict.
- AC3: A flag moves the agent from shadow to live per agent, and back.

### HFR-20 Keep a tamper-evident audit trail
- AC1: Proposals, verdicts, choices, approvals and applies write an `audit_entry` with `prev_hash` and `hash` (SHA-256 over the previous hash and the canonical entry).
- AC2: A nightly job verifies the chain and alerts on any break.
- AC3: Audit rows cannot be updated or deleted (database triggers, like the F4 and ledger tables).

### HFR-21 Protect fairness and standing
- AC1: Scoring and prompts contain no protected attributes, no location and no contact or social data.
- AC2: An applied option is "on plan". The credit-record feed carries the case id and the "on plan" marker.
- AC3: The risk level is never an input to eligibility, pricing, limits or graduation. An architecture test enforces that `credit-record` and `underwriting` do not read hardship signals.
- AC4: A monthly check on the simulator cohorts compares alert rates across neutral cohort tags and reports the ratio.

### HFR-22 Publish events and integrate cleanly
- AC1: Events: `HardshipSignalDetected`, `HardshipOfferIssued`, `HardshipOptionChosen`, `HardshipDecisionMade`, `HardshipApplied`, `HardshipCaseClosed`, all with `eventId`, `caseId`, `loanId`, `memberId` and `businessDate`, delivered at least once.
- AC2: Hardship sets an open-case flag in lending through a facade command (`setHardshipOpen`, see [02-design.md](02-design.md)), so F4's retry rule ("no retry while a case is open") needs no call into hardship.
- AC3: Consumers: engagement (messages), credit-record ("on plan"), desk-query (queue), reserve (case closed).

### HFR-23 Observe everything
- AC1: Each case has one trace from scan, through agent run, proposal, verdict, outreach, choice, approval and apply.
- AC2: Metrics include alerts per 100 loans, time to first outreach, time to decision, validator rejection rate, grounding failures, cost per case and fallback rate.
- AC3: Alerts fire on SLA breach, kill-switch trip and audit-chain break.

## 3. Non-functional requirements

| ID | Requirement | Target |
| --- | --- | --- |
| HNFR-01 | Scorecard for one loan | ≤ 1 ms |
| HNFR-02 | Daily scan of 100,000 active loans across shards | ≤ 10 min |
| HNFR-03 | Validator for one proposal | p95 ≤ 200 ms |
| HNFR-04 | One agent run (up to 6 tool calls, one model call at 20 s timeout) | p95 ≤ 30 s |
| HNFR-05 | Case created → first outreach sent (outside quiet hours) | ≤ 15 min |
| HNFR-06 | Approval recorded → schedule applied | p99 ≤ 10 s |
| HNFR-07 | Kill switch tripped → all outreach on templates | ≤ 2 s |
| HNFR-08 | Availability with the model down | Scan, cases, offers, approvals and applies 100% available; narrative falls back to templates |
| HNFR-09 | Privacy | Zero PII fields in any stored or sent prompt (checked by a scanner over stored prompts); log retention 30 days |
| HNFR-10 | Cost | At most $0.02 per case on average, and a daily budget cap that trips the breaker (targets) |
| HNFR-11 | Readability | Messages at about grade 6 to 8, English and Spanish |
| HNFR-12 | Audit integrity | Nightly chain verification finds 0 breaks |
| HNFR-13 | Detection quality on synthetic cohorts | Precision at alert at least 0.5 and recall at least 0.7 of misses caught 5 or more days early (targets; synthetic data only) |
| HNFR-14 | Delivery semantics | At-least-once everywhere; exactly-once effect through unique keys (`approvalRef`, `Idempotency-Key`, dedupe key) |
| HNFR-15 | Security | Members see only their own case (404 otherwise); desk needs staff roles; jobs need the service token; agents only tokens scoped to one loan |

## 4. Invariants (checked by tests and by a nightly invariant job)

| ID | Invariant |
| --- | --- |
| HINV-1 | No schedule change happens without the member's recorded choice AND the approval its tier requires. |
| HINV-2 | Agents have no write path. The only action is `submit_proposal`, and a proposal is untrusted until the validator accepts it. |
| HINV-3 | Every number, date and option shown to a member or officer comes from a tool result or the engine. Otherwise the template is shown. |
| HINV-4 | The checker is not the maker, and neither is the member. |
| HINV-5 | Applying is idempotent on `approvalRef`. At most one `APPLIED` per (case, option). |
| HINV-6 | A hold is append-only, lasts at most `holdDays`, exists once per (loan, instalment, case), and is never extended. |
| HINV-7 | Hardship never lowers standing. An applied option is "on plan". The risk level is never used for eligibility, pricing, limits or graduation. |
| HINV-8 | With the AI off, every flow still completes. |
| HINV-9 | At most one open case per loan. |
| HINV-10 | The audit hash chain is unbroken, and audit rows are never updated or deleted. |
| HINV-11 | After an apply, Σ instalments = principal (F4 INV-1), and re-folding the money history leaves outstanding, overpayment and money paid in advance unchanged (PROP-RSC-01). |
| HINV-12 | Outreach respects quiet hours, the per-instalment and per-30-day limits, and `STOP`. |
| HINV-13 | An agent token is valid for one loan and one case, for at most 10 minutes. |
| HINV-14 | The option a member chose, the option an officer approved and the option applied share one snapshot hash. If the schedule version changed, the offer is rebuilt and reconfirmed. |
| HINV-15 | No option carries a fee or interest (F4 INV-5). |

## 5. Traceability: requirement to test families

Test IDs are defined in [06-test-plan.md](06-test-plan.md). The families are `TC-HSG` (signals and scorecard), `TC-HOP` (options), `TC-HCS` (case lifecycle), `TC-HAP` (approval and separation of duties), `TC-HEX` (execution), `TC-HAPI` (API and contract), `TC-AGT` (agent behaviour and evals), `TC-INJ` (injection), `TC-FAIR` (fairness), `TC-SEC` (security), `PROP-H` (property tests), `CHAOS-H`, `PERF-H` and `E2E-H`.

| Requirement | Test families |
| --- | --- |
| HFR-01 Score the risk | TC-HSG, PROP-H, PERF-H |
| HFR-02 Early enough | TC-HSG, TC-FAIR, PERF-H, E2E-H |
| HFR-03 Alert hygiene | TC-HSG, TC-HCS, PROP-H |
| HFR-04 Member asks for help | TC-HCS, TC-HAPI, E2E-H |
| HFR-05 AI second opinion | TC-AGT, TC-HSG, CHAOS-H |
| HFR-06 Options | TC-HOP, PROP-H |
| HFR-07 Policy caps | TC-HOP, TC-HAPI |
| HFR-08 Proposal validation | TC-AGT, TC-INJ, TC-HAPI, CHAOS-H |
| HFR-09 Outreach | TC-HCS, TC-AGT, E2E-H |
| HFR-10 Member choice | TC-HCS, TC-HAPI, TC-HEX |
| HFR-11 Human approval | TC-HAP, TC-SEC, TC-HAPI |
| HFR-12 Apply | TC-HEX, PROP-H, CHAOS-H, E2E-H |
| HFR-13 Grace hold | TC-HCS, TC-HEX, PROP-H |
| HFR-14 Lifecycle | TC-HCS, PROP-H |
| HFR-15 Desk | TC-HAPI, TC-HAP, E2E-H |
| HFR-16 Scoped read tools | TC-SEC, TC-AGT, TC-HAPI |
| HFR-17 Untrusted text | TC-INJ, TC-AGT |
| HFR-18 Degrade and stop | CHAOS-H, TC-AGT, E2E-H |
| HFR-19 Shadow mode | TC-AGT, TC-HCS |
| HFR-20 Audit | TC-HAP, TC-SEC, CHAOS-H |
| HFR-21 Fairness and standing | TC-FAIR, TC-SEC, TC-HEX |
| HFR-22 Events and integration | TC-HEX, TC-HAPI, TC-HCS |
| HFR-23 Observability | TC-HAPI, CHAOS-H, PERF-H |

## 6. Out of scope for F5

- Computing the credit record and levels (F6). F5 hands over the "on plan" marker and the case id.
- Reserve cover and staff-led handling of loans at 60 or more days past due (F8).
- The conversational Coach and open questions from members (F9).
- Training or serving a machine-learning risk model in production. A challenger model in shadow is a possible follow-up after the hackathon.
- Claims of real-world accuracy or legal compliance. All quality numbers come from synthetic cohorts, and regulations are used as design inputs, not as legal advice.
- Interest, fees, new money and multi-currency. USD only, 0% only.
- Building the member and desk web screens. This document set specifies the APIs and the evidence they must show.
