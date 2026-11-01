# AGENTS.md — PayPal Seed

Guidance for coding agents (and humans) working in this repository. Read the part you are about to change before you change it.

PayPal Seed is savings and fair microloans built into PayPal, with an AI safety net that offers help before a payment is missed. Three parts:

| Part | Path | Stack |
| --- | --- | --- |
| Backend (all money logic) | `backend/` | Java 21, Spring Boot 3.3, Spring Modulith, jOOQ, PostgreSQL 16 |
| Web (landing, member app, officer desk) | `web/` | Next.js 16, React 19, Tailwind v4, shadcn, AG Grid, Bryntum |
| API contracts | `contracts/openapi/*.yaml` | OpenAPI 3.1, the source of truth for both sides |

Feature docs live in `docs/features/*`, decisions in `docs/adr/*`.

## Ground rules

- **Contract first.** Change `contracts/openapi` before the code that implements or calls it.
- **The backend decides; the web displays.** Money, dates, schedules, risk and eligibility are computed in Java and shown as given.
- **AI proposes, code checks, people decide.** No model output reaches a member or changes a loan without passing the deterministic checks and, where required, two human approvals.
- **Prove it.** Run the build of the part you changed and say what you ran. Never skip, weaken or delete a test to get green.
- **No secrets in the repo.** Keys come from the environment. Demo identity headers stay off outside dev, test and demo.

## Agent skills

Installed project skills (in `.claude/skills`, git-ignored; `skills-lock.json` records them):

```bash
npx skills add vercel-labs/agent-skills -s vercel-react-best-practices -s vercel-composition-patterns -s web-design-guidelines -a claude-code -y --copy
```

| Skill | Use it when |
| --- | --- |
| `vercel-react-best-practices` | Writing or reviewing any React or Next.js code: data fetching, waterfalls, bundle size, rendering |
| `vercel-composition-patterns` | Designing components: compound components, context, avoiding boolean-prop sprawl |
| `web-design-guidelines` | Reviewing UI for accessibility, interaction and visual polish |

Design reference: `web/DESIGN.md` (ElevenLabs system via `npx getdesign@latest add elevenlabs`).

---

# Backend (`backend/`)

A modular monolith that runs as one jar in three roles: `api`, `ingress` (PayPal webhooks) and `worker` (pollers, close of business, scans).

| Path | What lives there |
| --- | --- |
| `engines/schedule`, `engines/rules` | Pure Java maths: schedules, allocation, aging, reschedules; hardship scorecard, policy, grounding checker |
| `modules/<name>` | One bounded context per module, one Postgres schema per module |
| `platform/*` | Kernel (Money, BusinessClock, errors), persistence (Flyway per schema, jOOQ config), security (Actor), testing |
| `app` | Boot application, error handler, background jobs, integration tests |
| `architecture-tests` | Spring Modulith verification and ArchUnit rules |
| `tools/jooq-codegen` | Generates jOOQ classes per module from its own migrations (ADR 0008) |

Modules: `lending` (schedules, repayments, close of business), `payments` (all PayPal calls), `ledger` (double entry), `engagement` (reminders, SMS), `hardship` (safety net), `governance` (maker-checker, audit hash chain), `agent-gateway` (model access, agent tokens, tools, kill switch), `guardian` (the AI agent), `underwriting` (consented income).

## Commands

```bash
./gradlew build                                   # -Werror compile, unit, property and integration tests
./gradlew :modules:hardship:test
./gradlew :app:test --tests "com.paypalseed.HardshipLifecycleIT"
```

Run locally (PostgreSQL 16 on any free port):

```powershell
docker run -d --name seed-postgres -e POSTGRES_USER=seed -e POSTGRES_PASSWORD=seed_password -e POSTGRES_DB=paypalseed -p 5434:5432 postgres:16-alpine
$env:DATABASE_URL = "jdbc:postgresql://localhost:5434/paypalseed"; $env:DATABASE_USER = "seed"; $env:DATABASE_PASSWORD = "seed_password"
$env:SEED_ROLE = "worker"; $env:SPRING_PROFILES_ACTIVE = "demo,dev"
./gradlew :app:bootRun                            # http://localhost:8080
```

Integration tests start their own embedded PostgreSQL. On Windows a running `bootRun` locks module jars: stop it before `./gradlew clean`.

## Architecture rules (enforced by tests)

1. **Modules talk only through `api` packages** (facade, `api.dto`, `api.events`). Spring Modulith `verify()` fails on any other dependency or a cycle.
2. **Engines are pure.** No Spring, no `java.sql`, no `java.net`, no module imports.
3. **jOOQ only in `infrastructure.persistence`.** Use cases and controllers see records.
4. **Only `payments` talks to PayPal. Only `ledger` writes the journal.**
5. **Agents never write.** The `guardian` reaches only the `agent-gateway` and `hardship` APIs: read tools plus one action, submitting a proposal. Code (`OutreachProposalValidator`, checks V01–V12) judges every proposal.

## Coding rules

- **Money** is `long` cents. **Business dates** come from `BusinessClock.today()`, instants from `clock.now()`. Never `LocalDate.now()` in domain code.
- **Transactions:** a use case owns its transaction. Cross-module commands that must commit with the caller use `MANDATORY` propagation. Never swallow an exception inside a transaction you expect to commit.
- **Events:** records in `api.events`, published inside the transaction. Listeners are `@ApplicationModuleListener` (async, new transaction, at least once), so each is idempotent on a business key.
- **Idempotency:** every external write carries a key (`Idempotency-Key`, PayPal transaction id, `approvalRef`). Same key, different body: `409`.
- **Append-only facts** (schedule versions, transactions, journal, missed instalments, audit entries) are protected by database triggers. Corrections are new rows.
- **Errors:** `DomainException` with a module `ErrorCode`; `ApiErrorHandler` returns RFC 9457 problems with a stable `type`. No stack traces leave the process.
- **Security:** controllers take an `Actor` and check its role; another member's resource returns `404`. Secrets come from the environment; personal data is redacted before any model call.
- **Style:** match the surrounding code. Records for DTOs, constructor injection, no Lombok, `-Xlint:all -Werror`. Comments explain why, never narrate.

## Adding things

- **Endpoint:** contract, controller in `infrastructure/web`, use case in `application/usecases`, integration test in `app/src/test`; then `pnpm gen:api` in `web/`.
- **Table:** a new `V<n>__<name>.sql` in the module's migration folder; never edit an applied migration.
- **Module:** copy the layout of `modules/hardship`, add it to `settings.gradle.kts`, apply `id("jooq-module")` if it owns tables.
- **AI capability:** model calls go through `ModelGateway` (budget, kill switch, breakers, redaction); new tools are read-only and scoped to the run token; add a validator check before trusting any new output field, and grounding and injection cases to the tests.

## Tests

| Kind | Where |
| --- | --- |
| Engine unit and jqwik property tests | `engines/*/src/test` |
| Module unit tests | `modules/*/src/test` |
| Integration tests (`*IT`, extend `IntegrationTest`: real PostgreSQL, `FakePayPal`, `FakeLlm`, `FakeBusinessClock`; call `settle()` after async events) | `app/src/test` |
| Architecture | `architecture-tests` |

---

# Web (`web/`)

Two Next.js apps in a pnpm + Turborepo workspace:

| App | Path | Port | Audience |
| --- | --- | --- | --- |
| `@paypal-seed/member` | `apps/member` | 3000 | Landing page (`/`) and the member app (`/app/**`) |
| `@paypal-seed/desk` | `apps/desk` | 3001 | Loan officers: hardship queue, approvals, agents, operations |

Packages: `@paypal-seed/ui` (design system), `@paypal-seed/api-client` (typed backend client), `@paypal-seed/timeline` (Bryntum), `@paypal-seed/i18n` (en/es), `@paypal-seed/config` (tsconfig, eslint).

## Commands

```bash
pnpm install
pnpm dev                       # both apps; the backend must run on :8080
pnpm typecheck && pnpm lint && pnpm build
pnpm gen:api                   # regenerate packages/api-client/src/generated from contracts/openapi
pnpm build:lowmem              # production build for a laptop: webpack, one worker, below-normal priority (peak ~1 GB)
```

`pnpm build` uses Turbopack on every core and can exhaust memory on a laptop with a few GB free; use `pnpm build:lowmem` there (it skips the TypeScript pass, so run `pnpm typecheck` first). A change is done when typecheck, lint and build pass and the page works in a browser against the real backend.

## Rules

1. **The browser never calls the backend.** Backend calls run on the server (`import "server-only"`) through `@paypal-seed/api-client`, with identity from the signed session cookie. Identity headers and `SEED_INTERNAL_TOKEN` never reach client code, props or logs.
2. **Types come from the contracts** via `pnpm gen:api`. Never hand-write a response type the contract defines.
3. **Format, never compute.** `formatMoney(cents)`, `formatBusinessDate("2026-11-20")`. Business dates are plain dates; never parse them in local time.
4. **Never show a member their risk level or score.** Members see options in plain words; officers see evidence.
5. **Every write is idempotent.** One `Idempotency-Key` per user action, created on the server. On `409` (stale offer, reused key) re-read and ask the user to confirm.
6. **Errors are RFC 9457.** Map known `type` codes (`sod-violation`, `hardship-offer-stale`, `hardship-policy-violation`, `agent-disabled`) to plain messages. Never show stack traces or raw JSON.
7. **AI text is labelled and never the source of a number.** Figures on screen come from structured fields.

## Next.js 16 conventions

- App Router, React 19, Server Components by default; `"use client"` only at the smallest interactive leaf.
- `params`, `searchParams`, `cookies()` and `headers()` are async. Route protection lives in `proxy.ts` (formerly `middleware.ts`).
- Mutations: Server Actions + `useActionState`, input validated with zod, then revalidate the route.
- Fetch in parallel and stream with `<Suspense>` and `loading.tsx` skeletons; no waterfalls in layouts.
- AG Grid, Bryntum and PayPal load with `next/dynamic` (`ssr: false`) inside a same-size skeleton.
- Check `node_modules/next/dist/docs` before using an API you are unsure about.

App layout:

```
src/
  app/                 routes only: thin pages, layouts, loading/error/not-found
  features/<feature>/  components/, server/ (server-only queries and mapping), actions.ts, schema.ts
  server/              env.ts (zod), session.ts (signed cookie), api.ts (client per request)
  proxy.ts             route protection
```

## Design system

- `web/DESIGN.md`: off-white canvas, warm near-black ink, light serif display (Newsreader 300 for the licensed Waldenburg), Inter body, ink pill buttons, hairline cards, pastel gradient orbs as atmosphere only.
- Use `@paypal-seed/ui` tokens (`bg-canvas`, `text-ink`, `border-hairline`); never inline hex. New shadcn components go into `packages/ui`.
- WCAG 2.2 AA: keyboard reachable, visible focus, labels, `aria-live` for async results, 44px touch targets, `prefers-reduced-motion`.
- Member copy is warm, short and pressure-free; the member can always say no.

## Sponsor integrations (keep them real)

| Tool | Where | Rule |
| --- | --- | --- |
| PayPal JS SDK | member "Pay now" | The backend creates and captures orders; the SDK only collects approval |
| AG Grid (Community) | desk queues | Theming API mapped to our tokens; infinite row model over the keyset API |
| Bryntum Scheduler Pro | member and desk timelines | Client-only; the previous plan drawn as ghost events |
| Render | `infra/render.yaml` | Web apps deploy as services next to the backend roles |

## Don't

- Don't add a client state library; server components plus URL state cover this app.
- Don't call internal job endpoints except from desk operations and member demo seeding, both server-only.
- Don't commit `.env.local`; `.env.example` documents every variable.
