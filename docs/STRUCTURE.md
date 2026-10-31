# PayPal Seed — Repository Structure

Surface-level layout for the build described in [plan.md](../plan.md). It fixes **where things live and which way dependencies point**; it does not design individual files.

**Stack change from plan.md §19:** the backend is **Java 21 + Spring Boot 3 (modular monolith)** and the front ends are **Next.js (App Router)**. Everything else in the plan (ledger rules, engines, PayPal flows, agents, sponsors) is unchanged.

---

## 1. Shape of the system

**One backend deployable, split into strictly bounded modules, run in three roles. Two Next.js apps. Agents and Render Workflows sit outside and talk to the backend only through contracts.**

```text
            ┌──────────────── web/ (Next.js) ────────────────┐
            │  member app (Seed tab)     desk app (officers)  │
            └───────────────┬──────────────────┬─────────────┘
                            │  OpenAPI (contracts/)
 PayPal webhooks ──► ┌──────▼──────────────────▼──────────────┐ ◄── agents/ (Astropods, read-only scopes,
                     │        backend/  seed-app (one jar)      │       proposals only)
                     │  role=api │ role=ingress │ role=worker   │ ◄── workflows/ (Render Workflows,
                     │  ─────────────────────────────────────── │       thin triggers for daily close etc.)
                     │  modules/: identity savings lending ...  │
                     │  engines/: schedule rules assessment     │
                     │  platform/: kernel outbox inbox security │
                     └──────┬───────────────────────┬──────────┘
                            │                       │
                  Postgres (schema per module)   Key Value · Elastic · Zapier · PayPal APIs
```

### Why a modular monolith, built to split

| Principle | How it shows in the repo |
| --- | --- |
| Hard module boundaries | Each bounded context is its own Gradle module with a public `api` package; everything else is internal. Spring Modulith + ArchUnit tests fail the build on a forbidden import. |
| Data ownership | One Postgres schema per module, migrations owned by the module. No cross-schema joins or foreign keys. |
| Async by default | Modules talk through domain events written to a transactional outbox (Spring Modulith event publication registry). Swapping the in-process bus for Kafka is configuration, not a rewrite. |
| One synchronous exception: the ledger | A money state change, its journal entry and its outbox event commit in **one transaction** (plan §16). Money modules call `ledger.api` in-process for that reason. |
| Same artifact, many roles | `seed-app` starts as `api`, `ingress` or `worker` via `SEED_ROLE`. Each role scales on its own (HTTP load, webhook bursts, batch jobs) without a second codebase. |
| Pure core | Schedule, rules and assessment engines are plain Java with no Spring, no IO: deterministic, property-tested, sub-5 ms. |
| Contracts first | OpenAPI and event schemas in `contracts/` generate the Java interfaces, the TS client, Postman collections and the APIMatic SDK. |
| Extraction path | A module that outgrows the monolith already has its own schema, API package and event contracts; moving it to its own service is a deployment change plus replacing in-process calls with HTTP/events. |

---

## 2. Top-level tree

```text
paypal-seed/
├── backend/                  # Java 21, Spring Boot 3, Gradle (Kotlin DSL) — the modular monolith
├── web/                      # Next.js apps + shared TS packages (pnpm + Turborepo)
├── agents/                   # Astropods blueprints: coach, underwriting, guardian, trust, desk-analyst + evals
├── workflows/                # Render Workflows (TS): thin, durable triggers into backend job endpoints
├── contracts/                # Single source of truth for every interface
│   ├── openapi/              #   seed-api.yaml (public + desk), internal-jobs.yaml, agent-tools.yaml
│   └── events/               #   AsyncAPI / JSON Schema for every domain event (plan §16)
├── postman/                  # collections, environments, simulator (chaos) configs, monitors
├── sdk/                      # APIMatic-generated clients (record verification for partners)
├── infra/
│   ├── render.yaml           #   Render Blueprint: seed-api, seed-ingress, seed-worker, web apps, Postgres, Key Value
│   ├── docker-compose.yml    #   local Postgres, Elastic, Key Value
│   └── elastic/              #   index templates, ES|QL fraud rules
├── tests/
│   ├── e2e/                  #   Playwright: save → unlock → apply → approve → disburse → repay → pause → finish
│   └── load/                 #   k6 scenarios, synthetic member generator
├── scripts/                  # sandbox seeding, demo clock control, PayPal plan/product bootstrap
├── docs/
│   ├── STRUCTURE.md          #   this file
│   └── adr/                  #   architecture decision records
├── plan.md
├── LICENSE                   # Apache-2.0
└── README.md
```

---

## 3. Backend (`backend/`)

```text
backend/
├── settings.gradle.kts
├── gradle/libs.versions.toml         # version catalog
├── build-logic/                      # convention plugins: java-library, spring-module, engine, testing
│
├── app/                              # seed-app: the only bootable module
│   └── src/main/
│       ├── java/.../SeedApplication  #   wiring, role selection (api | ingress | worker)
│       └── resources/                #   application.yml + application-{role}.yml, -{env}.yml
│
├── platform/                         # shared kernel — small, stable, no business rules
│   ├── kernel/                       #   Money, typed ids, BusinessClock (demo clock), Result/errors
│   ├── persistence/                  #   datasource, jOOQ config, Flyway per schema, row-lock helpers
│   ├── messaging/                    #   outbox, inbox dedupe, event publication, retry + dead letter
│   ├── idempotency/                  #   idempotency keys (Key Value + table), request hashing
│   ├── security/                     #   PayPal login (members), passkeys (staff), roles, agent read-only scopes
│   ├── observability/                #   tracing (OTel → Elastic), metrics, structured logs, PII redaction
│   └── testing/                      #   Testcontainers base, fixtures, fake clock, PayPal mock
│
├── engines/                          # pure Java libraries: no Spring, no IO, property-tested (jqwik)
│   ├── schedule/                     #   generate, allocate, age, reschedule
│   ├── rules/                        #   eligibility ladder, sizing, hardship options, approval tiers, fraud blocks
│   └── assessment/                   #   fair-check numbers: income avg, lowest month, CV, saving consistency
│
├── modules/                          # bounded contexts (one Gradle module + one DB schema each)
│   ├── identity/                     #   member, staff, consent (grant/revoke), wallet preference, language
│   ├── savings/                      #   F1: goals, deposits, withdrawals, top-ups, pause, streaks
│   ├── lending/                      #   F2, F4: eligibility, applications, loans, schedules, repayments, late buckets
│   ├── underwriting/                 #   F3: consented data fetch, assessment records, card requests to agent
│   ├── hardship/                     #   F5: signals, cases, options offered/chosen, apply via schedule engine
│   ├── credit-record/                #   F6: record projection, levels, signed share links, verification API
│   ├── ledger/                       #   F7a: accounts, journal, posting rules, balances, reconciliation
│   ├── governance/                   #   F7: proposal → verdict → approval (maker-checker) → execution, audit hash chain
│   ├── payments/                     #   PayPal: executor (only write client), webhook ingress, sagas, txn search
│   ├── reserve/                      #   F8: donations, coverage, cover requests
│   ├── fraud/                        #   F10: signals (Elastic), flags, dispute evidence packs
│   ├── engagement/                   #   reminders, SMS/Slack/Sheets via Zapier, in-app notifications
│   ├── agent-gateway/                #   read-only tool endpoints for agents, proposal intake, untrusted-text extractor, kill switch
│   └── desk-query/                   #   read models for the desk (portfolio, queues, AG Grid server-side rows)
│
└── architecture-tests/               # Spring Modulith verify, ArchUnit rules, contract conformance
```

### 3.1 Inside every module (one template, applied everywhere)

```text
modules/<name>/
├── build.gradle.kts
└── src/
    ├── main/java/com/paypalseed/<name>/
    │   ├── api/              # PUBLIC: facade interface, commands, DTOs, published events — the only importable package
    │   ├── domain/           # aggregates, value objects, invariants — no Spring, no persistence
    │   ├── application/      # use cases, transaction boundaries, ports (interfaces to the outside)
    │   └── infrastructure/
    │       ├── web/          # REST controllers implementing generated OpenAPI interfaces
    │       ├── persistence/  # jOOQ repositories for this module's schema
    │       ├── events/       # listeners for other modules' events
    │       └── clients/      # external adapters this module owns (e.g. Elastic in fraud, Zapier in engagement)
    ├── main/resources/db/migration/<name>/   # Flyway migrations for schema `<name>` only
    ├── test/                 # unit + slice tests
    └── integrationTest/      # Testcontainers Postgres, module-in-isolation (Spring Modulith @ApplicationModuleTest)
```

### 3.2 Dependency rules

| From | May depend on |
| --- | --- |
| `app` | everything (wiring only) |
| any module | `platform/*`, `engines/*`, other modules' `api` packages only |
| `engines/*` | `platform/kernel` only |
| `platform/*` | nothing in `modules/` or `engines/` |
| `domain/` packages | `kernel` and engines only — no Spring, no jOOQ |

Hard rules carried from the plan:

- **Only `payments` holds PayPal write credentials.** Other modules request money movement through `payments.api` commands; results come back as events.
- **Only `ledger` writes journal tables.** Money modules call `ledger.api.post(...)` inside their own transaction; the source key makes it idempotent.
- **Agents never call a write endpoint.** `agent-gateway` exposes read tools and accepts proposals; `governance` turns proposals into verdicts and approvals; the target module executes.
- `desk-query` only consumes events and reads its own projections; it never writes to another module.

### 3.3 Runtime roles (same jar)

| Role | Runs | Scales on |
| --- | --- | --- |
| `api` | REST for member app, desk, partners, agents; SSE | request rate |
| `ingress` | PayPal webhook verify → inbox → fast 200 | webhook bursts |
| `worker` | outbox relay, event listeners, sagas, daily close steps, reconciliation | queue depth, batch size |

---

## 4. Front end (`web/`)

```text
web/
├── package.json · pnpm-workspace.yaml · turbo.json
├── apps/
│   ├── member/                       # Seed tab inside PayPal/Venmo + public pages
│   │   └── src/
│   │       ├── app/                  # Next.js App Router
│   │       │   ├── (auth)/           #   Log in with PayPal
│   │       │   ├── (member)/         #   savings, loans, record, coach, hardship
│   │       │   ├── (public)/         #   verify/[token] (record check), safety-fund (Donate SDK, transparency)
│   │       │   └── api/              #   BFF route handlers: session, SSE proxy — tokens never reach the browser
│   │       ├── features/             # one folder per feature: components, hooks, server actions, view models
│   │       ├── lib/                  # api client instance, auth/session, PayPal JS SDK v6 loader, i18n (en, es)
│   │       └── middleware.ts         # auth gate, locale
│   └── desk/                         # loan officer desk
│       └── src/
│           ├── app/
│           │   ├── (auth)/           #   staff passkeys
│           │   └── (desk)/           #   applications, approvals, portfolio, hardship, reserve, fraud, analyst, dead-letters
│           ├── features/             # AG Studio widgets, queues, approval flows, Desk Analyst panel
│           ├── lib/
│           └── middleware.ts         # auth gate, role checks
└── packages/
    ├── api-client/                   # generated from contracts/openapi — types + fetch client
    ├── ui/                           # design system: tokens, primitives, layout
    ├── timeline/                     # Bryntum Scheduler Pro wrapper (client-only), used by member and desk
    ├── i18n/                         # shared message catalogs (en, es)
    └── config/                       # tsconfig, eslint, tailwind presets
```

Two apps rather than one: different identity (PayPal login vs staff passkeys), different bundles (AG Studio is heavy), different exposure (member app is public-facing). Both deploy and scale independently.

---

## 5. Outside the backend

```text
agents/
├── coach/ · underwriting/ · guardian/ · trust/ · desk-analyst/
│   ├── blueprint.yaml        # Astropods blueprint: model, tools (read-only), knowledge stores
│   ├── prompts/
│   └── evals/                # accuracy, clarity, fairness, injection corpus
└── shared/                   # tool definitions generated from contracts/openapi/agent-tools.yaml

workflows/                    # Render Workflows (TS) — orchestration only, no business logic
├── daily-close/              #   batches loans → POST backend /internal/jobs/age-loans
├── payouts/                  #   disbursement + withdrawal sagas (durable retries)
├── hardship-scan/            #   Guardian daily run
└── reconcile/                #   nightly PayPal cash vs ledger
```

Render Workflows is TS-first, so it stays a thin trigger layer: each step calls an idempotent backend job endpoint. All money logic stays in Java. If Render Workflows is dropped, the same endpoints run from the `worker` role's scheduler.

---

## 6. Tech choices (one line each)

| Concern | Choice |
| --- | --- |
| Backend | Java 21 (virtual threads), Spring Boot 3, Spring Modulith, Gradle Kotlin DSL |
| Data access | jOOQ + Flyway, Postgres (schema per module), ledger constraint triggers and row locks |
| Cache / idempotency / rate limits | Render Key Value (Redis-compatible) |
| Events | Transactional outbox (Spring Modulith); Kafka externalization when needed |
| API | OpenAPI-first; openapi-generator for Spring interfaces and the TS client |
| Front end | Next.js App Router, TypeScript, PayPal JS SDK v6, Donate SDK, Bryntum, AG Studio |
| Testing | JUnit 5, jqwik (property), Testcontainers, Spring Modulith tests, ArchUnit, Postman/Newman, Playwright, k6 |
| Observability | OpenTelemetry → Elastic; Astropods agent spans |
