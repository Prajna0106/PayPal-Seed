# PayPal Seed — What Is Built and What Is Pending

Status as of **3 Nov 2026**. The plan and roadmap are in [plan.md](../plan.md).

Priorities: **P0** is needed for the demo, **P1** wins sponsor prizes or adds clear value, **P2** only if time is left.

Submissions close **12 Nov 2026, 12:00 PT**, ten days from this status.

---

## Last verified

`./gradlew build` with every task re-run passes: **1,448 tests, 0 failures, 0 errors, 0 skipped** across the engines, platform, modules, architecture tests and the `app` integration tests. Every module compiles with `-Xlint:all -Werror`. `pnpm gen:api` and the typecheck of both web apps and all packages pass against the merged contract. The US sandbox app answers for Orders, Subscriptions (catalog), Payouts and Transaction Search (checked with direct calls on 3 Nov 2026), but the full journey has not run on it yet (see PayPal sandbox), and the web screens have not been clicked through in a browser.

Two test-suite notes from the final run:

- `settle()` in the integration test base now re-submits stale incomplete event publications while it waits. Production does the same from `BackgroundJobs` every minute; tests switch that job off. This closed a race where a refund listener could run before its payment was recorded.
- A savings report for a goal that does not exist yet is retried for `seed.savings.goal-visibility-window` (an hour in production, one second in the test profile so that tests naming goals which never exist still finish), then dropped.

---

## Summary

| Area | Built | Main gaps |
| --- | --- | --- |
| Backend | F1 to F6, identity, PayPal Payouts, savings subscriptions, Transaction Search, Venmo wallet, credit record, desk read models, reserve, fraud flags, member home call, signed actor tokens, ledger, governance, engagement, agent gateway. Full build passes: 1,448 tests | Webhook, catalog bootstrap and the full sandbox journey still to run; see PayPal sandbox |
| Contracts | Every route is in `contracts/openapi`, events in `contracts/events`; a test fails when a route and the contract disagree; the web client is generated from it | `agent-tools.yaml` uses the tool names as operation ids on purpose |
| Frontend | Landing page; member Log in with PayPal, Home, savings, borrow, record, fund, shop, settings; desk queues, portfolio, workload, reserve, fraud, operations. Builds pass | Not clicked through in a browser yet; Spanish copy; a few fields the backend does not return yet |
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

Closed in the last backend pass (ADRs 0024 to 0027):

- **A payout returned after the loan was registered** now voids the loan in code: one reversing ledger entry, nothing owed, the application goes back to `APPROVED` with `payoutIssue=RETURNED_AFTER_DISBURSEMENT` so an officer can send it again with a new payout reference, what the member already paid is refunded through PayPal, and the record, desk, reserve, engagement and hardship all drop the loan (`LoanVoided`). The cooling-off cancel uses the same path and now closes its loan as `CANCELLED`.
- **Reserve cover that the schedule did not take** (a member paid at the same moment) goes back to the fund by an idempotent ledger entry (`CoverReturned`).
- **Fair-check consent** is in the identity ledger and the member's consent list (`FAIR_CHECK`); withdrawing it from the list withdraws the underwriting consent.
- **Record sharing consent** is checked when a share link is created (`409 record-sharing-consent-required`); withdrawing it revokes the member's links.
- **Policy, loan paid by the safety fund:** the loan is `REPAID` and the member is not penalised, but an instalment the fund paid in full counts as neither on time nor late, and a loan the fund paid entirely shows as `completedWithFundHelp` instead of a member-completed loan.
- **Transaction Search** is read before the submission transaction opens; a PayPal outage returns `503` before anything is written, and fresh stored income needs no PayPal call.
- **Fraud rule for payout recipients** compares a keyed hash of the recipient (withdrawals and disbursements), never the raw account.
- **A savings webhook that beats the goal's commit** is retried for up to an hour (`seed.savings.goal-visibility-window`), then dropped. PayPal reports are ordered by `update_time`, then the webhook time, then receipt time.
- **Pause and resume stamps** use an injected real-time clock on purpose (ADR 0027).
- **Desk cursors** need `SEED_DESK_QUERY_CURSOR_KEY`; start-up now fails without it outside dev, test and demo. The saving streak counts months in the business time zone. The application SLA (`PT24H`) is a setting.
- **`payoutRecipientReady`** on the member home call tells the web to ask for a PayPal login before a withdrawal.
- **Postman:** the F5 hardship folder (34 requests) is in a new v3 collection, `postman/collections/PayPal Seed API v3/`. It has not been run against a backend.

- **Held refunds are durable:** a refund PayPal cannot make (void, cooling-off cancel, a late payment on an unwound loan) is kept as a row, retried with a doubling delay (`seed.lending.held-refund.max-attempts` 6, `retry-delay` PT15M), and ends as `FAILED` with an audit entry that the desk can see.
- **Cooling-off cancel** settles to exactly zero and refunds what the member paid; a refused application revokes only a consent that the request itself created; `MicroloanApplicationService` uses the injected clock; the preview says `incomeStale` when PayPal could not be read.
- **Home call:** `recentActivity` (newest first, `seed.home.activity-limit`), `savings.goals[].nextDepositDate`, and `creditRecord.nextStep.requiredOnTimeSavingMonths` beside `onTimeSavingMonthsToGo`. New `GET /api/v1/savings/goals/{goalId}/deposits` and `GET /api/v1/desk/underwriting/assessments/latest?applicationId=`.
- **Desk deposit months** are rebuilt once at the first start after this ships (business time zone).
- **Savings pull sync (ADR 0028):** `POST /api/v1/savings/goals/{goalId}/sync` and a 15-minute job (`POST /internal/jobs/savings/reconcile`) read the subscription and its completed payments from PayPal and apply them through the webhook's own use cases, so a goal activates and is credited even when no webhook reaches the backend (local runs, or a missed webhook). The return page and a "Check with PayPal" button on the goal call it. Checked on the sandbox on 3 Nov 2026: a goal went `ACTIVE` with its first sale credited.
- **Venmo payouts are off** (`SEED_PAYPAL_VENMO_PAYOUTS_ENABLED=false`, the sandbox refuses them): every member is paid through PayPal. Venmo-funded top-ups still work.

Pending:

- [ ] **P0 Sandbox: webhook, catalog and the journey.** The US sandbox app (Subscriptions, Payouts, Transaction Search, Log in with PayPal) answers on 3 Nov 2026. Still to do: run `scripts/paypal-bootstrap.sh` against a running backend, register the webhook once there is a public `ingress` URL, and run the full journey with two US sandbox personal accounts.
- [ ] **P1 A refund that PayPal cannot make for an overpaid `REPAID` loan** only logs when PayPal has too little left to refund; the held-refund job covers voided and cancelled loans only.
- [ ] **P2 The cooling-off cancel confirmation** (engagement) does not say a refund is coming.
- [ ] **P2 With a frozen test clock** desk snapshots can tie on the same instant (a flake risk in tests, ADR 0024; production clocks move).
- [ ] **P2 Loan activity in Home history** is dated at the start of the business day (loan events carry a date only), and history before this release is mostly absent except deposits.
- [ ] **P2 New settings outside dev:** `SEED_PAYMENTS_RECIPIENT_FINGERPRINT_KEY` and `SEED_DESK_QUERY_CURSOR_KEY` on every backend role (`render.yaml` generates both).

---

## Frontend

Built (typecheck, lint and the low-memory production build pass for both apps; the new screens have not been clicked through in a browser yet):

- **Landing page**: inclusion story, journey, safety net, guardrails, FAQ; Framer Motion; mobile layout.
- **Member app**
  - **Login:** Log in with PayPal (server-side OpenID Connect), signed actor tokens, demo login behind `SEED_DEMO_LOGIN`.
  - **Shell and Home:** navigation with every route, and Home from one `GET /members/me/home` call (savings, loan, borrow, record, help, open application; a failed section shows a calm note).
  - **Savings:** goals list, create a goal (coach suggestion, PayPal approval redirect and return page), goal detail with top-up (PayPal or Venmo buttons), pause, resume and withdraw; the Bryntum journey timeline.
  - **Borrow:** a five-step flow (what is unlocked, amount with a live monthly payment, consent, fair check, review with the full schedule), applications list and detail with the cooling-off cancel, loans list.
  - **Settings:** profile and the consent ledger (revoking income consent revokes both consents).
  - **Record:** the credit record page, share links (consent first, link shown once), and a public verify page at `/verify/{token}`.
  - **Fund and shop:** a public safety-fund page at `/fund` with PayPal Donate, and `/app/shop`, saving for a product with Channel3 (a labelled demo catalog until `CHANNEL3_API_KEY` is set).
  - **Loan pages:** loan page with timeline and PayPal Pay now, hardship help page.
- **Desk**
  - **Sidebar and queues:** grouped sidebar; applications queue and fair-check queue (AG Grid), with approve, reject, disburse and the decision form.
  - **Portfolio and workload:** portfolio KPIs, inclusion impact and loan grid; officer workload in Bryntum.
  - **Reserve and fraud:** reserve cover requests (maker and checker, with signers and the SLA badge) and fraud flags with the evidence pack.
  - **Existing screens and demo tools:** persona login, overview, hardship queue and case, loan view, agents kill switch, operations with demo buttons for the hardship scan and sweep.

Pending, member app:

- [ ] **P0 Click through every screen against the real backend and the PayPal sandbox** (login, create a goal and approve it, top up, withdraw, apply, repay). Nothing new has been seen in a browser; the PayPal redirects and buttons, the clipboard copy and the Venmo button are the places to look first.
- [ ] **P1 Home extras the backend does not return yet**: recent Seed activity, a months-to-go number for the next unlock, a next deposit date on a goal, and deposit history (the savings timeline starts at the goal's creation date for lack of it).
- [ ] **P1 Savings details**: an amount change on a goal is not built; the withdrawal status updates on reload only.
- [ ] **P1 Channel3**: set `CHANNEL3_API_KEY` and check the real response shape; the adapter parses defensively and drops anything that does not fit. The buy action opens the merchant's checkout, not PayPal.
- [ ] **P2 Spanish** copy for the new pages (they are English only), and a review of the Spanish that exists, by a native speaker.
- [ ] **P2 Public donate amount** is limited to $1 to $1,000 in the browser only; the backend caps it too.

Pending, desk:

- [ ] **P0 Click through the desk screens** with two different officers (maker and checker) on the real backend.
- [ ] **P1 Fair check beside an application**: the contract has no lookup by application, so the page reads the whole list and picks the newest check; add a lookup before the list gets long.
- [ ] **P1 Cover form**: needs member and loan ids typed in. A link from the hardship case page to `/reserve?memberId=&loanId=&hardshipCaseId=` would fill them.
- [ ] **P2 SLA badges** compare with the real clock, so they can be wrong when the demo clock has been moved.
- [ ] **P2 Workload bars** use default lengths where a bucket has no deadline (layout only; all counts come from the backend).

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

- [x] **P0 Log in with PayPal** on the web side: authorization code flow with PKCE on the server, then `POST /internal/members/login`. Set `PAYPAL_LOGIN_CLIENT_ID`, `PAYPAL_LOGIN_CLIENT_SECRET`, `APP_ORIGIN` and the app's return URL.
- [ ] **P0 Web servers sign actor tokens** instead of sending `X-Seed-*` headers. Done for the member app; the desk still sends dev headers.
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
| P1 | 0013 | **Checked 3 Nov:** Payouts work on the US app; the create response has the batch header only and the gateway reads the batch for the item (as built) |
| P2 | 0013 | **Checked 3 Nov:** 56-character references accepted (a 57-character batch id too) |
| P3 | 0013 | **Checked 3 Nov:** the sandbox refuses Venmo payouts ("Recipient wallet not supported in sandbox"); Venmo payouts are off, members are paid through PayPal |
| P4 | 0013 | Webhook payload shape of `PAYMENT.PAYOUTS-ITEM.*` (are `sender_batch_id` and `sender_item_id` both there) |
| P5 | 0013 | **Checked 3 Nov:** `SOFTWARE` is accepted |
| P6 | 0013 | `PayPal-Auth-Assertion` is honoured for a sandbox pair, and which `transaction_event_code` sandbox payouts show |
| S1 | 0017 | **Partly checked 3 Nov:** a `PAYPAL_ID` payout is accepted at create (a made-up id); confirm with a real login payer id in the journey |
| S2 | 0017 | Revising the quantity of a subscription asks the payer to agree again |
| S3 | 0017 | `SUSPENDED`, `CANCELLED` and `EXPIRED` webhooks arrive when the payer acts in PayPal, with `custom_id` |
| S4 | 0017 | **Checked 3 Nov:** a `-a1` suffix is accepted |

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
