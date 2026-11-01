# PayPal Seed — Plan

Status as of **2 Nov 2026**. Submissions close **12 Nov 2026, 12:00 PT**.
The full list of open work, by area, is in [docs/STATUS.md](docs/STATUS.md). How to work in the repo is in [AGENTS.md](AGENTS.md).

---

## 1. The problem and the product

Opening a bank account or getting a loan usually means documents, a credit score, a minimum balance and a branch visit. Hundreds of millions of people who earn, save and pay on time cannot clear that bar: gig workers with irregular income, people new to credit, people who keep their money in cash or a wallet, young adults and newcomers. When they need to borrow, the options left are expensive.

**PayPal Seed is a PayPal integration that gives underbanked people basic banking, starting with savings and microloans.** Members save a small fixed amount each month through PayPal. A few months of steady saving is the proof of reliability, so it unlocks a 0% loan without a credit score or paperwork. Seed then looks after the member: it schedules repayments, offers help before a payment is missed, and turns good behaviour into a record that unlocks bigger loans.

Rules that shape every feature:

- **0% interest, $0 fees.** Not for paying late, paying early, or asking for help.
- **No credit score.** Eligibility comes from saving behaviour and the member's own PayPal income, read only with consent.
- **Numbers from code, words from AI, decisions from people.** AI explains and proposes; code computes every figure; a human approves anything that changes a loan.
- **The member can always say no**, and asking for help never hurts their standing.

---

## 2. Features and where they stand

| # | Feature | What it does | Backend | Web | AI |
| --- | --- | --- | --- | --- | --- |
| F1 | Goal savings | Fixed monthly saving toward a named goal through PayPal (Venmo by one-off top-up) | **Built**: the backend creates the PayPal subscription and the goal waits for the member to agree; top-up, withdraw through PayPal Payouts, pause, resume, amount change, Venmo wallet | Not started | **Built**: Savings Coach |
| F2 | Savings-linked microloans | Three months of steady saving unlocks a 0% loan | **Built**: eligibility ladder lifted by the credit record level, sizing, applications, approve, reject, disburse through Payouts (DISBURSING until PayPal confirms), cooling-off cancel | Not started | Coach suggests a term |
| F3 | Fair loan check | AI reads consented savings and PayPal income, suggests a safe amount with reasons; an officer approves | **Built**: consent, assessments, dispute, officer decision, income from Transaction Search behind a flag (seeded income stays for demos) | Not started | **Built**: Fair Check narrator |
| F4 | Repayment schedule and autopay | Instalments, due dates, autopay, pay now, late tracking | **Built**: schedules, autopay, orders and capture, close of business, aging, payoff quote | **Built**: member loan page, timeline, Pay now; desk loan view | — |
| F5 | Hardship safety net | Spots a likely missed payment early, offers a pause or a smaller longer plan; two officers approve | **Built** end to end | **Built**: member help page; desk queue, case, approvals, agents, operations | **Built**: Guardian, live on `google/gemini-3.1-flash-lite` |
| F6 | Credit record | A verified history of saving and repayment that unlocks bigger loans and can be shared | **Built**: record projection, five levels, loan limit lift, signed share links, public partner verification, desk read | Not started | — |

Shared backend pieces that are built: double-entry **ledger**, **governance** (maker-checker, hash-chained audit log), **engagement** (reminders, SMS through Zapier), **payments** (PayPal REST gateway, Payouts, catalog bootstrap, Transaction Search, webhook ingress), **agent gateway** (model access, budget, kill switch, PII redaction, grounding checks), **identity** (members, staff, consent ledger, signed actor tokens), **reserve** (community safety fund and cover requests), **fraud** (advisory flags with evidence), **desk-query** (keyset read models for the desk grids) and the member **home** summary call. Every route is in `contracts/openapi`, and a test fails if a route and the contract disagree.

The landing page is built and tells the financial-inclusion story.

---

## 3. The member experience we are building

1. **Log in with PayPal.** No forms, no documents. The PayPal account is the identity.
2. **Home.** Seed balance, savings goals and streak, loan and next payment, record level, and recent Seed activity in PayPal.
3. **Create a goal.** Name it, pick an amount from $5 a month, approve the recurring payment in PayPal.
4. **Build a streak.** Each month's deposit lands by webhook. The coach nudges, never pressures.
5. **Unlock a loan.** After three on-time months, Seed shows what is unlocked and why.
6. **Fair loan check.** The member consents to Seed reading their PayPal income. Code computes a safe amount; AI explains it in plain words; an officer approves.
7. **Apply and receive.** The member sees the full schedule before accepting, has a cooling-off window, and gets the money in PayPal.
8. **Repay.** Autopay on the chosen day, or pay now with PayPal. Five days of grace, never a fee.
9. **Get help early.** If income drops before a due date, Seed reaches out with options (built).
10. **Build a record.** On-time saving and repayment raise the record level and unlock a bigger loan. The member can share a verifiable record with another lender.

**"See my PayPal funds".** PayPal's public APIs do not let a third-party app read a member's wallet balance. Log in with PayPal returns identity (name, email, payer id, verified status), and the balance and transaction APIs return data for the app's own account. So:

- **In the product pitch**, Seed is a first-party PayPal feature that would read the wallet internally.
- **In the sandbox demo**, Home shows money Seed holds and moves (savings, loan, payments, all from our ledger and PayPal webhooks) plus consented income from a seeded dataset, clearly labelled as sandbox data.

---

## 4. The officer experience

The desk (separate app, staff only) has: loan applications, fair-check decisions, hardship cases, the loan portfolio, the AI agents and their kill switch, and operations. Every change to a member's money needs the right role, and plan changes need two different officers.

Built today: overview, hardship queue and case, loan view, agents, operations. Missing screens (the backend for all of them is built): applications, fair-check decisions, portfolio and impact, workload, reserve cover requests, fraud flags.

---

## 5. Architecture as built

- **Backend** (`backend/`): Java 21, Spring Boot 3.3, Spring Modulith modular monolith, jOOQ, PostgreSQL 16 or 17, one schema per module. One jar runs as `api`, `ingress` or `worker`. Contracts in `contracts/openapi` are the source of truth.
- **Web** (`web/`): two Next.js 16 apps in a pnpm and Turborepo workspace. `member` (port 3000) is the landing page and the member app; `desk` (port 3001) is the officer desk. The browser never calls the backend; server code does, with a signed session cookie.
- **AI**: three agents (Guardian, Savings Coach, Fair Check) run inside the backend's `agent-gateway` and reach models through OpenRouter. They read through scoped tools and can only submit proposals that code validates.

Differences from the original target layout in [docs/STRUCTURE.md](docs/STRUCTURE.md):

| Planned | Today |
| --- | --- |
| Agents hosted on Astropods | Agents run in the backend; `agents/` holds descriptors and placeholders |
| Render Workflows trigger the daily jobs | Spring schedulers in the `worker` role; `workflows/` is placeholder |
| Elastic, Render Key Value | Not used yet |
| Log in with PayPal and staff passkeys | Demo personas and a signed cookie; the backend trusts dev identity headers in dev and demo only |
| PayPal Payouts and Transaction Search | Not wired; disbursement and withdrawal record a payout id, income is seeded through an internal endpoint |

Decisions are recorded in [docs/adr](docs/adr). Feature designs for F4 and F5 are in [docs/features](docs/features).

---

## 6. Roadmap to submission

Priorities: **P0** is needed for the demo, **P1** wins sponsor prizes or adds clear value, **P2** only if time is left.

| Dates | Work | Priority |
| --- | --- | --- |
| 1–3 Nov | PayPal sandbox app, products and plans; Log in with PayPal and the identity module; member Home and savings goals UI with subscription approval | P0 |
| 4–6 Nov | Eligibility, fair check and loan application UI; desk applications and fair-check queues in AG Grid; Payouts for disbursement and withdrawal | P0 |
| 7–8 Nov | Credit record (F6) minimum; Channel3 product goals; Bryntum savings-to-loan timeline | P1 |
| 9 Nov | Deploy to Render with Workflows; APIMatic SDK and portal; Postman collection for F5 and the full journey | P1 |
| 10 Nov | End-to-end pass on the deployed sandbox, seed demo data, fix bugs | P0 |
| 11 Nov | Record the demo video (under 3 minutes) and write the Devpost submission | P0 |
| 12 Nov, morning | Final checks and submit before 12:00 PT | P0 |

---

## 7. Sponsor plan

Each sponsor does real work in the product. Prize amounts are from PayPal's announcement; confirm the rules on Devpost before submitting.

| Sponsor | Prize | Integration that solves a real problem | Status |
| --- | --- | --- | --- |
| **AG Grid** | 1st $5,000, 2nd $2,000, three at $1,000 | The desk runs on grids: hardship queue (done), loan applications, fair-check decisions with evidence in master/detail, and a portfolio and inclusion-impact grid (members served without a credit score, on-time rate, hardship outcomes) | Hardship queue built; the backend keyset API for the other grids is built, the screens are not |
| **Bryntum** | three at $1,000 | One timeline from first deposit to last instalment: saving months, the unlock date, loan instalments, and the old plan as ghost events after a hardship change. On the desk, an officer workload view of approvals by SLA | Loan timeline built; the workload read model is built, the screen is not |
| **APIMatic** | three at $1,000 | Generate SDKs and a developer portal from `contracts/openapi`. Partner use: another lender verifies a member's Seed credit record through the generated SDK, which is what makes the record portable | Not started |
| **Channel3** | $1,500 | Save for a real thing: the member picks a product (a laptop for school, a work tool) from the Channel3 catalog, the goal amount is the real price, and when the goal is reached they buy it with PayPal after approving. Also fits "Best Use of Agentic Commerce" | Not started |
| **Render** | credits $1,000, $750, $500 | Host every service from `infra/render.yaml`; move the daily hardship scan, close of business, payouts and reconciliation to Render Workflows so each run is durable and visible | Blueprint written (secrets, groups and manual steps documented), not deployed |
| **Postman** | Tool partner | Collections for every feature, a mock server for the web apps, monitors on health and the daily scan, a CI run | Collection covers F1–F4, identity, catalog bootstrap, credit record, desk queues, reserve, fraud flags and home; F5 not yet |
| **Astropods** | Tool partner | Host the three agents and run their evals: grounding, prompt injection, fairness | Not started |
| **Elastic** | Tool partner | Agent and case dashboards: rejection rate, grounding failures, time to decide | Not started |
| **Zapier** | Tool partner | SMS offers and replies, Slack alerts for SLA breaches | SMS built |
| **Kernel** | Tool partner | Stretch: with consent, an agent reads gig-platform earnings for members whose income is outside PayPal, to strengthen the fair check for thin-file workers | Not started |

PayPal itself is used for identity, recurring saving (Subscriptions), disbursement and withdrawals (Payouts), repayments (Orders and the JS SDK), and income (Transaction Search). That covers "Best Use of PayPal + AI".

---

## 8. Demo story (under 3 minutes)

1. The problem in one line, then Ana logs in with PayPal.
2. She starts a $25 a month goal and approves it in PayPal. The demo clock skips three months.
3. Her loan unlocks. She consents to the fair check; the AI explains a safe amount; an officer approves it in the AG Grid queue.
4. The money lands in PayPal. Her Bryntum timeline shows saving and repayment as one picture.
5. Her income drops. Seed reaches out first; she picks a smaller plan; two officers approve.
6. Her record improves and a partner verifies it through the APIMatic SDK.

---

## 9. Risks

- **Laptop memory.** Run Postgres natively, start one web app at a time, and use `pnpm build:lowmem`.
- **Sandbox limits.** Payouts and webhooks need a reachable URL; use the Render deployment or a tunnel.
- **Unverified rules.** The Devpost rules page was not readable by automated tools. Check deliverables, video length and sponsor prize claims by hand.
- **Scope.** P0 is a complete member journey on the real sandbox. Cut P2 work first.
