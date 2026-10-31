# F4 — Sponsor Integrations

The rule from plan §13 applies: a sponsor is used only where it carries real work, and every use has a demo moment. F4 is where money meets time, so Render, Postman, Bryntum and AG Grid carry the most weight here.

| Sponsor | F4 job | Load-bearing because | Demo moment |
| --- | --- | --- | --- |
| **Render** | Workflows run COB, reconciliation and mandate sync; Postgres (+ read replica); Key Value; Blueprint; preview environments | Money steps must be durable and retried; COB is the bank's heartbeat | The Render run tree for `daily-close`: 8 shards in parallel, one retried, all green |
| **Postman** | Contract-first collection, mock server, webhook replay suite, Simulator chaos, monitors, Flows, performance runs, CI gate | It proves the invariants hold under PayPal failure | Simulator: 503s and duplicate webhooks, and the ledger still balances with no double posting |
| **Bryntum** | Scheduler Pro timeline of instalments, grace windows, and the previous plan as ghost events | Repayment *is* a schedule; the hardship shift is the story | Ana's instalments slide one month; the old plan stays visible as dashed ghosts with arrows |
| **AG Grid** | Server-side row model for the loan book, aging pivot, roll-rate heatmap, KPI widgets, Desk Analyst tools | Officers run collections from here | "Which loans roll to 8–30 this week?" builds a filtered grid and a widget |
| **Zapier** | SMS reminders and replies, Slack alerts, Google Sheets collections report | Members live on their phones | Ana gets "Your $150 payment is on Friday" and later replies PAY to get a link |
| **Elastic** | Event index, ES\|QL repayment-abuse rules, APM traces webhook → ledger | Signals over money events, plus end-to-end tracing | One trace from PayPal webhook to journal entry in under 1 s |
| **Astropods** | Guardian consumes F4 signals; Coach explains the schedule with what-if previews | Agents read F4 state; they never change it | Coach answers "What if I pay $50 extra?" with exact numbers from the preview endpoint |
| **APIMatic** | Context Plugin while building PayPal Subscriptions and Invoicing; spec linting; partner servicing SDK (stretch) | The lending partner needs a servicing feed | `seed.loans.getServicing(id)` in three lines |

---

## 1. Render

**Workflows (`workflows/daily-close/`, TypeScript, orchestration only; every step calls an idempotent backend job):**

```ts
// sketch: Render Workflows task graph
export const dailyClose = task({ name: 'daily-close', retry: { maxRetries: 5, backoff: 'exponential' } },
  async ({ businessDate }) => {
    for (const d of await missingDatesUpTo(businessDate)) {              // catch-up: never skip a date
      const { shardCount } = await post(`/internal/jobs/cob/${d}/start`);
      await Promise.all(range(shardCount).map(s => ageShard({ date: d, shard: s })));   // fan-out: one subtask per shard
      await post(`/internal/jobs/cob/${d}/finish`);
    }
    await post('/internal/jobs/mandates/reconcile');
    await post(`/internal/jobs/repayments/reconcile/${businessDate}`);
  });

export const ageShard = task({ name: 'age-shard', retry: { maxRetries: 8 } }, async ({ date, shard }) => {
  let r; do { r = await post(`/internal/jobs/cob/${date}/shards/${shard}`); } while (!r.done);   // resumable from the cursor
});
```

- Triggered by a Render cron at 00:30 Pacific, and by `POST /internal/demo-clock/advance` in the demo.
- **Fallback:** if Render Workflows is not available, the `worker` role runs the same endpoint sequence from a Spring scheduler, guarded by the `cob_run` primary key (STRUCTURE.md §5).
- **Postgres:** the ledger and lending schemas. Point-in-time recovery is on. The desk SSRM queries go to a **read replica**, so officer grids never contend with posting locks.
- **Key Value:** API idempotency response cache; Zapier inbound replay cache (5-minute window); per-member SMS token bucket (at most 3 per day).
- **Blueprint (`infra/render.yaml`):** env groups `paypal`, `zapier`, `internal-jobs`. `seed-worker` and the workflow service share the private network. `/internal/*` is not exposed publicly.
- **Preview environments** per PR run the Postman suite (§2) against a fresh database.

## 2. Postman

Contract-first, following the `postman:api-engineer` flow: contract → mock → implementation → tests → CI → cloud.

| Asset | Path | Content |
| --- | --- | --- |
| OpenAPI (source of truth) | `contracts/openapi/seed-api.yaml`, `internal-jobs.yaml`, `agent-tools.yaml` | Every endpoint in [02 §6](02-design.md#6-api) |
| Collection (v3 YAML, git-native) | `postman/collections/f4-repayments/` | Folders: `member-schedule`, `autopay`, `payments`, `desk`, `internal-jobs`, `webhook-replay`, `chaos`, `scenarios/ana` |
| Environments | `postman/environments/{local,preview,sandbox}.yaml` | Base URLs, service token, demo loan ids |
| Webhook fixtures | `postman/fixtures/webhooks/*.json` | Real sandbox payloads captured in spike S6, re-signed with the local test certificate |
| Mock server | from the spec | Unblocks `web/apps/member` (Bryntum timeline) and the desk grid before the backend lands |

**Tests in every request:** status code; response matches the OpenAPI schema; invariant checks in scripts, for example:

```js
pm.test('INV-1: instalments sum to principal', () => {
  const s = pm.response.json();
  pm.expect(s.instalments.reduce((t, i) => t + i.amountCents, 0)).to.eql(s.principalCents);
});
pm.test('INV-2: outstanding equals remaining', () => {
  const s = pm.response.json();
  pm.expect(s.instalments.reduce((t, i) => t + i.amountCents - i.paidCents, 0)).to.eql(s.outstandingCents);
});
```

**Simulator chaos scenarios (`postman/simulator/f4-chaos.json`):**

| Scenario | Injected | Expected |
| --- | --- | --- |
| C1 Duplicate storm | Same `PAYMENT.SALE.COMPLETED` × 10, in parallel | 1 repayment, 1 journal entry, 10 × 200 |
| C2 Two events, one payment | `INVOICING.INVOICE.PAID` and `PAYMENT.CAPTURE.COMPLETED` for one transaction | 1 repayment |
| C3 Out of order | Sale before `BILLING.SUBSCRIPTION.ACTIVATED` | Sale parked, then applied once activation commits |
| C4 PayPal 503 on revise | 503 × 3, then 200 | Command retried with the same request id; mandate converges |
| C5 Timeout on capture | Timeout after PayPal processed it | Executor re-observes; no second capture |
| C6 Reversal after payoff | `PAYMENT.SALE.REVERSED` on a REPAID loan | Loan reopens ACTIVE; bucket recomputed; `RepaymentReversed` |
| C7 Lost webhook | Sale never delivered | Watchdog at due + 1 ingests it with `source = RECONCILER` |
| C8 Late charge after closure | Sale arrives after REPAID | Auto-refund command; overpayment returns to 0 |

**Monitors:** `f4-cob-freshness` (daily 03:00 PT: yesterday's `cob_run` is COMPLETED), `f4-integrity` (hourly: `/desk/loans/{demo}/integrity` drift = 0), `f4-autopay-health` (hourly on sandbox: mandate observed less than 24 h ago).

**Performance:** `postman performance run` on `GET /schedule` (50 virtual users, p95 < 100 ms) and on the COB shard endpoint with 100k seeded loans.

**Flows:** "Ana's repayment month": advance the demo clock → run COB → replay the sale webhook → assert schedule and integrity. It is used in the video as the visual of the system working.

**CI:** a GitHub Actions job runs the collection against the app booted with Testcontainers on every PR. Pushing to the Postman cloud workspace happens on merge to `main`, only once the team has agreed to it.

## 3. Bryntum Scheduler Pro

`GET /api/v1/loans/{id}/timeline` returns data already shaped for the scheduler's stores:

```json
{
  "resources": [{ "id": "loan-4f1c", "name": "Mixer loan · $1,200" }],
  "events": [{ "id": "v2-s3", "resourceId": "loan-4f1c", "startDate": "2027-03-09", "endDate": "2027-03-10",
               "name": "$150", "status": "SCHEDULED", "eventColor": "blue" }],
  "ghostEvents": [{ "id": "v1-s3", "resourceId": "loan-4f1c", "startDate": "2027-02-09", "readOnly": true, "cls": "ghost" }],
  "dependencies": [{ "from": "v1-s3", "to": "v2-s3", "cls": "shift" }],
  "timeRanges": [{ "startDate": "2027-03-09", "endDate": "2027-03-14", "name": "Grace" }]
}
```

- Colours come from the **derived** status: paid green, due blue, in grace amber, late red, prepaid teal.
- SSE events `ScheduleChanged` and `InstalmentStatusChanged` → `eventStore.applyChangeset`, so the timeline redraws live while the officer approves.
- **Bryntum AI for officers:** "show Ana's plan with a May pause" → `POST /schedule/preview` (pure engine, no writes) → rendered as a preview layer the officer can discard. The engine's purity is what makes this safe.

## 4. AG Grid (AG Studio desk)

- **Server-side row model:** `POST /api/v1/desk/loans/query` takes the SSRM request directly. `filterModel` and `sortModel` map to jOOQ conditions through a **column allowlist**, so no raw column names reach SQL. Row grouping by `bucket` and aggregation (sum of outstanding) run in SQL on the read replica.
- **Master/detail:** expand a loan to see instalments (derived status) and repayments.
- **Widgets:** PAR30 gauge; late-bucket funnel; **roll-rate heatmap** (bucket at month start × bucket at month end, from `bucket_transition`); autopay first-attempt success sparkline; COB run status tile.
- **Live updates:** `LateBucketChanged` over SSE → `applyServerSideTransaction`.
- **Desk Analyst tools (read-only):** `query_loans` (same SSRM endpoint), `get_roll_rates`, `get_kpis`. "Which loans are likely to roll to 8–30 this week?" → loans in grace with a failed attempt and no retry planned.

## 5. Zapier

- **Outbound (deterministic, not via an LLM):** `engagement` POSTs to a Zapier Catch Hook with `{to, locale, template, vars, dedupeKey}` and an HMAC header. The Zap sends the SMS. Templates are kept in `engagement` (en and es, grade-6 reading level).
- **Inbound:** SMS reply → Zap → `POST /api/v1/webhooks/zapier/sms` (HMAC, timestamp, replay cache) → tool-less extractor → `PAY` (send Payment Link), `HELP` (open an F5 hardship conversation), `STOP` (opt out), `YES` (F5 option confirmation).
- **Slack:** COB status, drift, invariant violations, parked events older than 15 minutes.
- **Google Sheets:** one row per business date in "Seed collections": due count, collected, first-attempt success, retries, cures, PAR30.
- **Zapier MCP** is reserved for agents' staff-facing actions (for example the Desk Analyst posting a summary to Slack). Member money messages never depend on a model.

## 6. Elastic

- **Indices:** `seed-events-*` (all F4 events, PII-free, `loanId`, `memberRef` hashed); `seed-signals-*` (F10).
- **ES|QL rules over repayment behaviour (feeding F10):**

```esql
// Payer mismatch: a loan repaid from a PayPal account that is not the member's
FROM seed-events-*
| WHERE event_type == "RepaymentReceived" AND payer_ref != member_payer_ref
| STATS loans = COUNT_DISTINCT(loan_id) BY payer_ref
| WHERE loans >= 2
```

```esql
// Pay-then-reverse: repayments reversed within 30 days, by member
FROM seed-events-*
| WHERE event_type == "RepaymentReversed" AND days_since_payment <= 30
| STATS reversals = COUNT(*) BY member_ref
| WHERE reversals >= 1
```

- **APM:** traces from ingress to `ledger.post` (02 §10). The dashboard shows ingest latency p50/p99 and the collection funnel.
- **Alerting:** Elastic rule → Zapier → Slack.

## 7. Astropods

- **Guardian (F5)** subscribes to `RepaymentFailed`, `LateBucketChanged`, `GraceNudgeDue` and `InstalmentDueSoon`. It reads with `get_repayment_schedule` and `get_loan_aging`, and calls `POST /schedule/preview` to show each option's outcome. It never writes; proposals go through `agent-gateway` (ADR 0003).
- **Coach (F9)** explains the schedule: "Why is my last payment $151?" (remainder placement) and "What if I pay $50 extra?" (`POST /payments/preview`). Every number in an answer must come from a tool result.
- **Evals:** a schedule-explanation set where the expected numbers are computed by the engine. The eval fails if any number in the answer is not in the tool output.

## 8. APIMatic

- **Build time:** the Context Plugin gives accurate PayPal Subscriptions, Invoicing and Orders shapes in Claude Code while writing the executor.
- **Spec quality:** APIMatic validation on `seed-api.yaml` in CI (score must not drop).
- **Partner servicing SDK (stretch):** the licensed lending partner needs a servicing feed (schedule, payments, DPD per loan). `GET /api/v1/partner/loans/{id}/servicing` is generated into the APIMatic TypeScript SDK and portal next to F6's `verifyRecord`.
