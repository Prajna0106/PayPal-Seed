# Feature 5 — Hardship Safety Net

**Status:** Backend implemented and tested (see [Implementation status](#implementation-status)) · **Owner module:** `backend/modules/hardship` (with `governance`, `agent-gateway`, `engines/rules` and `desk-query`; collaborators are `lending`, `payments`, `engagement` and `credit-record`) · **Plan reference:** [README.md, feature 5](../../../README.md) · **Date:** 2026-11-01

When a member is likely to miss a payment, Seed notices early, offers a pause or a smaller and longer plan with exact numbers, and a human approves the change. The Guardian agent writes the words and offers a second opinion. Code detects risk and computes every number. The member chooses. An officer, and for bigger changes a second officer, approves. The schedule changes only when all of that has happened, and a hardship option counts as "on plan" in the member's record.

**Core principle: numbers from code, words from AI, decisions from humans.**

## What makes this design strong

1. **The safety net works with the AI switched off.** Detection is a deterministic, explainable scorecard. The AI only raises borderline cases (escalate-only) and writes the officer brief and the member message. If the model is down, slow, wrong or switched off, deterministic templates take over and nobody loses help ([ADR 0009](../../adr/0009-ai-proposes-code-computes-human-approves.md)).
2. **The agent has no write path.** It has read tools and one action: submit a proposal. A proposal is untrusted input. A deterministic validator recomputes it from source data before any human sees it ([ADR 0003](../../adr/0003-agent-proposal-governance.md)).
3. **Every number a human sees was produced by code.** A grounding check rejects any amount, date or option in a narrative that is not in a tool result. A failed check swaps in the template.
4. **Two consents, not one.** The member must choose an option, and the required approval must exist. Moving a date within 14 days is member consent plus automatic policy approval. A pause or a restructure needs an officer and a different checker (separation of duties, hash-chained audit log).
5. **Asking for help never hurts standing.** An approved option is "on plan" for the credit record. The risk level decides only whether to reach out. It is never used for pricing, limits or graduation ([ADR 0011](../../adr/0011-hardship-case-lifecycle-consent-hold-and-standing.md)).
6. **A bounded grace hold closes the approval gap.** If a member has chosen an option and the officer has not decided before grace runs out, a hold of up to 3 days stops the missed-payment fact from being recorded. Lending owns the hold, so there is no module cycle.
7. **Built on F4, not around it.** Previews and applies use the F4 reschedule primitives (`MOVE_DATE`, `DEFER_TO_END`, `REAMORTIZE`) through `LendingFacade`. Re-folding the same money history leaves the balance unchanged (PROP-RSC-01). The apply is idempotent on an approval reference.
8. **Production-style agent guardrails.** Scoped one-loan tokens that expire in 10 minutes, a closed-set extractor for member free text, per-run call, token and time caps, a cost budget, a circuit breaker, a kill switch, shadow mode before go-live, and a recorded run for every agent call. The controls map to the OWASP Top 10 for LLM Applications and NIST AI RMF ([04](04-agent-architecture.md)).

## Documents

| # | Document | What it answers |
| --- | --- | --- |
| 1 | [01-requirements.md](01-requirements.md) | Policy values, functional requirements with acceptance criteria, non-functional targets, invariants, traceability |
| 2 | [02-design.md](02-design.md) | Modules, case state machine, data model, API, events, security, observability |
| 3 | [03-algorithms.md](03-algorithms.md) | Scorecard, alert hygiene, option ranking, approval tiers, grace hold, SLA timers, grounding check, fairness and calibration checks |
| 4 | [04-agent-architecture.md](04-agent-architecture.md) | Guardian roles, tool contract, LLM gateway and runtime choice, guardrails, evals, shadow mode, kill switch |
| 5 | [05-sponsor-integrations.md](05-sponsor-integrations.md) | Sponsor tools with concrete jobs, hackathon fit, prize map, demo script |
| 6 | [06-test-plan.md](06-test-plan.md) | Test cases with IDs: property, unit, integration, agent evals, injection, fairness, chaos, E2E |
| 7 | [07-implementation-plan.md](07-implementation-plan.md) | Ordered tasks, done-when, estimates, dependencies, cut lines against the 12 Nov deadline |
| 8 | [08-run-guide.md](08-run-guide.md) | Running with or without an OpenRouter key, kill-switch modes, seeding and walking the Ana demo |

### Architecture decision records

- [ADR 0003 — Agent read-only scopes and proposal intake](../../adr/0003-agent-proposal-governance.md) (existing; F5 builds on it)
- [ADR 0009 — AI proposes, code computes, human approves](../../adr/0009-ai-proposes-code-computes-human-approves.md)
- [ADR 0010 — LLM gateway, agent runtime and the PayPal toolkit boundary](../../adr/0010-llm-gateway-agent-runtime-and-paypal-toolkit-boundary.md)
- [ADR 0011 — Hardship case lifecycle, consent, grace hold and standing](../../adr/0011-hardship-case-lifecycle-consent-hold-and-standing.md)

## Where this design changes plan.md and the repo scaffold

These are deliberate corrections. Each is explained in the linked document.

| Plan or scaffold says | This design | Why |
| --- | --- | --- |
| The Guardian agent "detects cashflow dips" (repository README) | Detection is a deterministic additive scorecard with reason codes. The AI may raise a borderline case by one level (escalate-only) and writes the narrative | Explainable, testable, and the safety net keeps working without the AI ([ADR 0009](../../adr/0009-ai-proposes-code-computes-human-approves.md)) |
| `agents/guardian/blueprint.yaml` pins `model: gemini-1.5-pro` | No model pinned in the blueprint. The agent calls an `LlmClient` port with a model fallback chain, first choice OpenRouter | One vendor outage or deprecation must not stop the net ([ADR 0010](../../adr/0010-llm-gateway-agent-runtime-and-paypal-toolkit-boundary.md)) |
| `role: read-only` with an empty tool list | Read tools plus exactly one action, `submit_proposal`, with a per-run token scoped to one loan | The agent needs to propose; it must never write ([04](04-agent-architecture.md)) |
| `contracts/openapi/agent-tools.yaml` has only `POST /agent/v1/proposals`; `agents/shared/agent-tools.yaml` is empty | A full read-tool contract and the proposal schema | Contract first ([04](04-agent-architecture.md)) |
| `workflows/hardship-scan/workflow.ts` only logs | A thin Render Workflow that fans out shard steps and calls idempotent job endpoints | Same pattern as the daily close ([05](05-sponsor-integrations.md)) |
| Option A is "auto-approved within policy" | Kept, but it also needs the member's recorded choice, and it is capped at 2 per loan per 6 months | Consent is never implied |
| Option C ("restructure") has no stated approval tier | Officer plus a different checker, like Option B | It changes the member's total time in debt |
| F4 requires "no retry if the member has an open hardship case" | Hardship sets lending's open-case flag and holds through lending facade commands, so lending never calls hardship | Avoids a lending to hardship dependency |
| F4 says asking for help *before* the miss keeps the record clean, with no mechanism for a slow approval | A bounded grace hold (3 days), owned by lending | Approval delay must not become the member's penalty |
| Kernel and Channel3 are sponsor tools | Not used | Browser automation and product data have no role here |

## Open spikes (day 1 to 2, before build)

Each spike has a fallback, so none blocks the build.

| ID | Question | Fallback |
| --- | --- | --- |
| SH1 | Astropods: blueprint format, runtime limits, eval harness, credits. Its public description is only "agent-native infrastructure to build, deploy, and run AI agents" | Run the same agent class in the `worker` role with the same ports |
| SH2 | PayPal MCP: exact tool names, sandbox token flow (`PAYPAL_ACCESS_TOKEN`), whether a hosted server exists | Use the MCP server only as a local dev and demo harness |
| SH3 | OpenRouter: provider data-collection opt-out, zero data retention, the `models` fallback array, structured-output support per model, cost reporting | A direct provider behind the same `LlmClient` port |
| SH4 | Devpost rules and resources: video length, licence, judging weights, credit codes | Follow the stricter reading until confirmed |
| SH5 | Zapier two-way SMS latency for the YES reply | Link in the SMS to the in-app choice |
| SH6 | AG Grid and AG Studio licence for the hackathon | Plain AG Grid community features plus charts of our own |
| SH7 | Bryntum Scheduler Pro trial licence and the drag-and-drop events | A read-only before and after timeline |

## Hackathon fit

Dates and the central requirement come from PayPal's official announcement. Everything else is labelled by how well it is confirmed.

**Verified (PayPal developer blog post, 30 Sep 2026):**
- Name: "Build What's Next with PayPal and AI". Submissions close **12 Nov 2026**. Winners are announced 21 Dec 2026.
- "Your project must meaningfully use both PayPal and AI." There are no tracks and no prescribed problem statements.
- Prizes of $60,000+ in cash. Honorable mentions of $5,000 each include Best Use of PayPal + AI, Best Use of Agentic Commerce, Most Impactful and Best Demo Delivery. Sponsor prizes exist for AG Grid, APIMatic, Bryntum, Channel3 and Render.

**Third-party summaries only (not checked against Devpost):**
- A working prototype, a public repository with an open-source licence (this repository is Apache-2.0), a public demo video under 3 minutes, the PayPal Sandbox, and judging on technical implementation, design, potential impact, innovation and presentation. No weights were found.

**Not verified:** the official `https://paypalaihackathon.devpost.com/rules` and `https://paypalaihackathon.devpost.com/resources` pages both returned HTTP 403 to automated fetches. Confirm by hand (spike SH4): the video length limit, the accepted licences, the judging weights, the resources and credit codes, and whether Zapier counts as an optional sponsor tool.

**How F5 meets "meaningfully use both PayPal and AI":** PayPal is the rails and the signal source (subscription failures and revisions, payment webhooks, Transaction Search income, Payment Links). The AI is the Guardian that explains and escalates. The money path stays inside our `payments` module, so PayPal's agent toolkit is a dev and demo harness, not a production dependency ([ADR 0010](../../adr/0010-llm-gateway-agent-runtime-and-paypal-toolkit-boundary.md)).

The demo story, the prize map and the sponsor jobs are in [05-sponsor-integrations.md](05-sponsor-integrations.md).

## Implementation status

**Built (backend, `backend/`):**

| Area | Where |
| --- | --- |
| Pure rules: policy v1, income features, scorecard with reason codes and evidence, hysteresis, escalate-only second opinion, option metrics and ranker, caps, approval tiers, snapshot hash, grounding checker (en/es), SLA clock, rubber-stamp detector | `engines/rules` (package `hardship`) |
| F4 support: idempotent and preview-checked `applyReschedule`, open-case flag that stops retries and nudges, grace holds with miss attribution, `riskFacts`, scan candidates | `modules/lending` (migration V3) |
| Consented weekly income summary and its seeding endpoint (stand-in for F3) | `modules/underwriting` |
| Proposals, validator SPI, verdicts, maker-checker approvals with separation of duties enforced in the database, SHA-256 hash-chained audit log and its verify job | `modules/governance` |
| Daily scan, event wake-ups, case lifecycle, offers A to D, template narratives, the `HARDSHIP_OUTREACH` validator (V01 to V12), member and desk endpoints, the execution saga, case timers, worker-role scheduler fallback | `modules/hardship` |
| Outreach SMS around a fixed header and footer, quiet hours, STOP, reply keywords A to D, NO and HELP routed to hardship | `modules/engagement` |
| Model gateway (OpenRouter client, kill switch LIVE/SHADOW/TEMPLATES_ONLY with auto-trip, daily budget, bulkhead, per-model breaker, retry), scoped run tokens, run trace, PII redaction, reason extractor, read tools, proposal intake | `modules/agent-gateway` |
| The Guardian: bounded tool-calling loop (6 calls, 45 s), escalate-only second opinion, strict-schema narrative, versioned prompts with their hash on the run, model-backed reason classifier | `modules/guardian` |
| OpenAPI | `contracts/openapi/seed-api.yaml`, `internal-jobs.yaml`, `agent-tools.yaml` |

**Verified:** a clean `./gradlew build`:

| Suite | Tests |
| --- | --- |
| `engines/rules` (unit and jqwik properties) | 44 |
| `modules/agent-gateway` | 39 |
| `modules/guardian` | 12 |
| `modules/governance` | 6 |
| `modules/engagement` | 6 |
| `modules/hardship` | 5 |
| `modules/lending` | 3 |
| `architecture-tests` (Modulith boundaries, jOOQ only in persistence, the Guardian reaches only the gateway and hardship APIs) | 6 |
| `app` integration tests on a real PostgreSQL 16 (F4 and F5), including `SafetyNetAiIT` for the AI-on end to end | 75 |

**Where the build differs from the design:**
- The case table is `hardship.hardship_case`, because `case` is a reserved word.
- Reason categories follow the agent tool contract: `INCOME_REDUCED`, `JOB_LOSS`, `MEDICAL`, `FAMILY_EMERGENCY`, `UNEXPECTED_EXPENSE`, `TIMING_ONLY`, `OTHER`.
- `previewHash` in proposals may be the full snapshot hash or its short form (`sha256:` plus the first 16 hex digits).
- A borderline `WATCH` case exists only for the Guardian's second opinion. Without an accepted escalation it closes quietly as `EXPIRED` (`NOT_ESCALATED`) and the member is never contacted.
- Option D stays `OPTION_CHOSEN` until the payment lands, which closes the case as `CANCELLED_PAID`.
- Moving the kill switch back to a less restrictive mode needs the `ADMIN` role; the second-person step in the design is not built.
- The circuit breaker opens after 5 consecutive failures and probes after 60 seconds, instead of a sliding failure-rate window.
- The desk endpoints read the hardship tables directly; there is no `desk-query` projection yet.

**Not built yet:**
- The `desk-query` hardship projection.
- Elastic indexes, ES|QL rules and APM traces.
- The Render workflow file for `workflows/hardship-scan` (the worker-role scheduler runs the same jobs).
- Postman collection updates, mock server and monitors for F5.
- The fairness and backtest harness on Simulator cohorts.
- The Seed MCP server.
- The member and desk front ends.
