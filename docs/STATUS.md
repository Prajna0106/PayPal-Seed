# PayPal Seed — What Is Built and What Is Pending

Status as of **2 Nov 2026**. The plan and roadmap are in [plan.md](../plan.md).

Priorities: **P0** is needed for the demo, **P1** wins sponsor prizes or adds clear value, **P2** only if time is left.

---

## Summary

| Area | Built | Main gaps |
| --- | --- | --- |
| Backend | F1 to F6, identity, PayPal Payouts, savings subscriptions, Transaction Search, Venmo wallet, credit record, desk read models, reserve, fraud flags, member home call, signed actor tokens, ledger, governance, engagement, agent gateway | Nothing run against the real PayPal sandbox yet; the checks are listed under PayPal sandbox |
| Contracts | Every route is in `contracts/openapi`, events in `contracts/events`; a test fails when a route and the contract disagree; the web client is generated from it | `agent-tools.yaml` uses the tool names as operation ids on purpose |
| Frontend | Landing page; member loan, timeline, Pay now, hardship help; desk hardship, loans, agents, operations | Member login, Home, savings, apply, fair check, record; desk applications, fair check, portfolio, workload, reserve, fraud |
| Agents | Guardian, Savings Coach, Fair Check, all through OpenRouter with kill switch and grounding checks | Astropods hosting and evals; Trust and Desk Analyst agents |
| Deployment | `infra/render.yaml` with secrets, groups and manual steps written down; Docker Compose | Nothing deployed; Render Workflows are placeholders; no edge rate limit |
| Authentication | Backend verifies signed actor tokens (key rotation, 30 s skew); dev headers only in dev, test and demo; service token only on `/internal/**` | The web servers do not sign tokens yet; Log in with PayPal on the web side; staff SSO |
| PayPal sandbox | REST gateway for Subscriptions, Orders, Payouts, catalog bootstrap, Transaction Search, webhooks; JS SDK Pay now | No sandbox app connected, so none of the new calls ran against PayPal |

---

## Backend

Built:

- **F1 savings**: the backend creates the PayPal subscription for a goal and the goal stays `PENDING_APPROVAL` until the member agrees at PayPal and the activation webhook arrives. Top-up (PayPal or Venmo), withdraw through Payouts, pause, resume, amount change, goal list, `POST /coach/suggest`. A goal not agreed within 24 hours expires.
- **F2 microloans**: eligibility from the saving streak and the credit record level (the larger limit applies), applications, desk approve (two different people), reject, disburse through Payouts (`DISBURSING` until PayPal confirms, then the loan and Schedule Version 1 are created), cooling-off cancel.
- **F3 fair loan check**: consent, assessments, dispute, officer decision. Income comes from Transaction Search when `seed.underwriting.income.source=TRANSACTION_SEARCH` (needs sandbox access); the seeded demo endpoint stays.
- **F4 repayment**: schedules and versions, autopay, pay now, overpayment refunds, payoff quote, timeline, close of business, aging. Reserve cover is applied to the schedule as a repayment from `RESERVE`.
- **F5 hardship**: daily scan, cases, options, member choice, maker-checker decisions, Guardian outreach, kill switch, retry.
- **F6 credit record**: five levels from saving and repayment events, a loan limit per level, signed share links with a public verification call for partners, desk read.
- **Identity**: members keyed by PayPal payer id, staff directory, profile, consent ledger with versions, signed actor tokens.
- **Desk read models**: application queue, fair-check queue, loan portfolio, portfolio summary with inclusion impact, approvals workload (keyset paging for AG Grid).
- **Reserve and fraud**: community safety fund with public summary, donation intent and two-person cover requests; advisory fraud flags with an evidence pack.
- **Member home**: one call for the Home page; a section that fails is named in `sectionErrors` and the rest still returns.
- **Platform**: double-entry ledger, governance audit chain, engagement (reminders, SMS through Zapier, withdrawal messages), webhook ingress with dedupe, idempotency keys, RFC 9457 errors.
- **Contract test**: `ContractCoverageIT` compares every route with the OpenAPI files. `DevDemoProfileBootIT` boots the application with the `dev` and `demo` profiles and no extra environment variables.

Pending:

- [ ] **P0 Run the new PayPal calls on the sandbox** (checks below).
- [ ] **P1 A payout returned after the loan was registered** is flagged `RETURNED_AFTER_DISBURSEMENT` but not unwound by code; an officer decides.
- [ ] **P1 Fair-check consent in the identity ledger**: the fair check keeps its own consent record; the member-facing consent list does not show it yet.
- [ ] **P1 Record sharing consent** (identity `RECORD_SHARING`) is not checked when a share link is created.
- [ ] **P1 A member without a PayPal login** gets `409 payout-recipient-missing` on withdrawal or disbursement. Correct, but the web has to explain it.
- [ ] **P2 Hardship in the Postman collection** (F5 requests are not in it yet).

---

## Frontend

Built:

- **Landing page**: inclusion story, journey, safety net, guardrails, FAQ; Framer Motion; mobile layout.
- **Member app**: demo login (Ana), `/app`, loan page with Bryntum timeline and PayPal Pay now, hardship help page.
- **Desk**: persona login, overview, hardship queue (AG Grid) and case with approvals, loan view, agents kill switch, operations.

Pending, member app:

- [ ] **P0 Log in with PayPal** button and callback (replaces "Start Ana's story"; keep the demo path behind a flag).
- [ ] **P0 Home**: Seed balance, goals and streak, loan and next payment, record level, recent Seed activity.
- [ ] **P0 Savings**: create a goal, approve the subscription in PayPal, top up, pause, resume, withdraw; coach suggestion.
- [ ] **P0 Eligibility and apply**: what is unlocked and why; consent screen; fair-check result in plain words; schedule preview; accept; cooling-off.
- [ ] **P1 Credit record** page and share link.
- [ ] **P1 Public fund page and Donate button** (`GET /api/v1/reserve/summary`, `POST /api/v1/reserve/donations/intent`); the PayPal order carries the returned `customId`.
- [ ] **P1 Bryntum savings-to-loan timeline**: deposits, unlock date and instalments on one timeline.
- [ ] **P1 Channel3 product goals**: pick a product, save for its price, buy it with PayPal when the goal is reached.
- [ ] **P2 Spanish** copy for the new pages, and a review of the Spanish copy that exists, by a native speaker (the i18n package exists).

Pending, desk:

- [ ] **P0 Loan applications** queue (AG Grid) with approve, reject and disburse.
- [ ] **P0 Fair-check decisions** queue with evidence in master/detail and the AI narrative labelled.
- [ ] **P1 Portfolio and inclusion impact** grid: loans by status and aging bucket, members served without a credit score, on-time rate, hardship outcomes.
- [ ] **P1 Officer workload** in Bryntum: approvals per officer against SLA.
- [ ] **P1 Operations button** to run the hardship scan and sweep for demos.
- [ ] **P1 Reserve cover requests** (maker and checker) and the fund balance; **P2 fraud flags** with the evidence pack. The backend for both is built.

---

## Agents

Built (inside `backend/modules/agent-gateway` and `guardian`):

- **Guardian** (F5): reads one loan through scoped tools, writes the officer brief and member message, checks V01–V12, grounding, injection handling; template fallback.
- **Savings Coach** (F1, F2) and **Fair Check narrator** (F3): explain figures that code computed, with grounding rules and a template fallback.
- **Model gateway**: OpenRouter, daily budget, concurrency limit, circuit breaker, PII redaction, kill switch (live, shadow, templates only).

Pending:

- [ ] **P1 Eval suites** for all three agents: grounding, injection corpus, fairness across income patterns. Run them in CI.
- [ ] **P1 Astropods**: host the agents and their evals there (descriptors exist in `agents/coach` and `agents/underwriting`; `guardian`, `trust` and `desk-analyst` are placeholders).
- [ ] **P2 Desk Analyst**: answers officer questions about the portfolio from read-only tools.
- [ ] **P2 Trust agent**: explains a member's credit record and what raises it.
- [ ] **P2 Agentic checkout**: Guardian option D and Channel3 goals request a PayPal checkout that the member approves.

---

## Deployment

Built: `infra/render.yaml` (backend as `api`, `ingress`, `worker`; both web apps; Postgres; groups `seed-shared` and `seed-backend`), `infra/docker-compose.yml`. Generated by Render: `SEED_INTERNAL_TOKEN`, `SEED_RECORD_SIGNING_SECRET`, `SEED_DESK_QUERY_CURSOR_KEY`, `SESSION_SECRET`. Set by hand (`sync: false`): `OPENROUTER_API_KEY`, `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_WEBHOOK_ID`, `SEED_PAYPAL_PRODUCT_ID`, `SEED_PAYPAL_SAVINGS_PLAN_ID`, `PAYPAL_LOAN_PLAN_ID`, `SEED_SAVINGS_RETURN_URL`, `SEED_SAVINGS_CANCEL_URL`, `DATABASE_URL` (the `jdbc:` form), `SEED_API_URL`, `NEXT_PUBLIC_*`.

One manual step Render cannot do: `SEED_ACTOR_TOKEN_SECRETS` must look like `k1:<32 or more characters>`, and Render only generates plain random values. Add it once to the `seed-shared` group in the dashboard so the backend and both web apps share it. The backend refuses to start without it outside dev, test and demo.

Pending:

- [ ] **P0 Deploy to Render** and set the values above.
- [ ] **P0 Public webhook URL** for PayPal on the `ingress` service; register it in the sandbox app.
- [ ] **P0 Edge rate limit.** Render has no built-in limiter, so put a CDN or WAF rule (about 60 requests a minute per IP) in front of the three public routes: `GET /api/v1/records/verify/{token}`, `GET /api/v1/reserve/summary` and `POST /api/v1/reserve/donations/intent`. Until that exists they are open to scraping; the verify answer is cached 5 seconds and the summary 30 seconds, which helps a little.
- [ ] **P1 Render Workflows** for the daily hardship scan, close of business, payouts and reconciliation (`workflows/` is placeholder today).
- [ ] **P1 CI**: backend build and tests, web typecheck, lint and build, Postman run.
- [ ] **P2 Render Key Value** for idempotency, rate limits and the kill switch cache.
- [ ] **P2 Elastic** for agent and case dashboards.

---

## Authentication

Built: signed `seed_session` cookie in both apps; route protection in `proxy.ts`; the backend verifies signed actor tokens on `/api/**` (HS256, key ids for rotation, short life), refuses a service token there and an actor token on `/internal/**`, checks roles on every call and enforces two different officers. Member routes work on the caller's own data; another member's resource is a `404`.

Pending:

- [ ] **P0 Log in with PayPal** on the web side (OpenID Connect authorization code flow, server side): after the callback the web server calls `POST /internal/members/login`, then signs a member actor token with `SEED_ACTOR_TOKEN_SECRETS`. Set the app's return URL and scopes. Keep the demo login behind a flag.
- [ ] **P0 Web servers sign actor tokens** instead of sending `X-Seed-*` headers (the API client already accepts a bearer token).
- [ ] **P2 Staff SSO** (an OIDC provider with roles from groups) instead of demo personas; the desk server then calls `PUT /internal/staff/{id}` after sign-in.

---

## PayPal sandbox

Built: `RestPayPalGateway` (Subscriptions create, revise, suspend, activate, cancel; Orders create and capture; refunds; Payouts; catalog bootstrap; Transaction Search; webhook signature verification), webhook ingress with dedupe and out-of-order handling, `@paypal/react-paypal-js` Pay now, `scripts/paypal-bootstrap.sh`. All tests use a fake PayPal.

Pending:

- [ ] **P0 Sandbox app**: create it on developer.paypal.com, set `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `NEXT_PUBLIC_PAYPAL_CLIENT_ID`, enable Payouts.
- [ ] **P0 Run `scripts/paypal-bootstrap.sh`** and set the three ids it prints.
- [ ] **P0 Webhook**: register the `ingress` URL, set `PAYPAL_WEBHOOK_ID`, check signature verification with real events.
- [ ] **P0 Run the full journey on the sandbox** with two sandbox personal accounts (member) and the business account (Seed).
- [ ] **P1 Transaction Search** access and `seed.paypal.transaction-search.enabled=true`.
- [ ] **P1 Log in with PayPal** app settings: return URL and scopes.

Facts the code assumes and nobody has checked against PayPal yet (the ADRs say how to test each):

| Id | ADR | What to check |
| --- | --- | --- |
| P1 | 0013 | Payouts permission is on for the sandbox app, and the create response carries the items |
| P2 | 0013 | Length limits of `sender_item_id` and `sender_batch_id` (56-character references) |
| P3 | 0013 | A Venmo payout works in the sandbox, and with which recipient type |
| P4 | 0013 | Webhook payload shape of `PAYMENT.PAYOUTS-ITEM.*` (are `sender_batch_id` and `sender_item_id` both there) |
| P5 | 0013 | The product category `SOFTWARE` is accepted |
| P6 | 0013 | `PayPal-Auth-Assertion` is honoured for a sandbox pair, and which `transaction_event_code` sandbox payouts show |
| S1 | 0017 | A `PAYPAL_ID` payout accepts the payer id from the login; a Venmo payout accepts an email |
| S2 | 0017 | Revising the quantity of a subscription asks the payer to agree again |
| S3 | 0017 | `SUSPENDED`, `CANCELLED` and `EXPIRED` webhooks arrive when the payer acts in PayPal, with `custom_id` |
| S4 | 0017 | A `-a<n>` suffix is accepted in `sender_item_id` |

Also unverified: that `update_time` is present on every subscription webhook resource (the code falls back to the event time, ADR 0020).

Limit to keep in mind: the public APIs do not expose a member's PayPal wallet balance to a third-party app. Home shows Seed balances and Seed activity instead (see [plan.md §3](../plan.md)).

---

## Testing and submission

- [ ] **P1 APIMatic**: generate the SDK and developer portal from `contracts/openapi` (the contract now covers every route, and a partner verifies a record through the public call). Not started.
- [ ] **P0 End-to-end check** of the full member and officer journey on the deployed sandbox.
- [ ] **P0 Demo video** under 3 minutes, public on YouTube.
- [ ] **P0 Devpost write-up**: problem, how PayPal and AI are used, each sponsor's role, setup steps.
- [ ] **P0 Confirm the rules by hand** (deliverables, licence, sponsor claim steps); see [F5 sponsor notes](features/f5-hardship-safety-net/05-sponsor-integrations.md).
- [ ] **P1 Postman**: add F5 to `postman/collections`, a mock server, and monitors. The collection already runs F1 to F4, identity, catalog bootstrap, credit record, desk queues, reserve, fraud and home against a dev backend.
- [ ] **P2 Playwright** journey test and k6 load scenario (`tests/` is placeholder).
