# PayPal Seed — Savings and Microloans, Built into PayPal

> **"Save a little, unlock a fair loan, and build a record, all inside the PayPal app you already use."**

PayPal Seed is a savings and microloan product for unbanked and underbanked people, integrated into PayPal and Venmo the way Instamart is built into Swiggy. Members save toward a goal; steady saving unlocks a fair 0% loan; AI helps them borrow an amount they can repay and protects them when a month goes wrong; every on-time payment becomes a verified record that opens bigger doors.

---

## The Problem & Opportunity

- **5.6 million US households** (4.2%) had no bank account in 2023; **19 million households** (14.2%) were underbanked.
- **33.8% of unbanked households** rely on prepaid cards or nonbank digital wallets such as PayPal, Venmo, or Cash App. For millions, PayPal *is* their primary financial hub.
- The #1 reason cited for being unbanked is **lack of money for minimum balances**.
- Traditional credit bureaus make millions "credit-invisible." By linking regular savings with microloans, saving habits prove the capacity to repay without a conventional credit score.

---

## Focused Scope: The First 6 Core Features

We focus on building and polishing the first 6 features of PayPal Seed, underpinned by our proprietary core banking ledger and deterministic engines:

```text
 ┌────────────────────────────────────────────────────────────────────────┐
 │                        MEMBER JOURNEY (F1 – F6)                        │
 │                                                                        │
 │  [1. Goal Savings] ──► [2. Savings-Linked Loan] ──► [3. Fair Check]   │
 │         │                       │                           │          │
 │         ▼                       ▼                           ▼          │
 │  Monthly $5+ Deposits     3+ Months Saving            Consented Data   │
 │  PayPal Subscriptions    0% Loan up to $1,500        Deterministic Math│
 │                                                                        │
 │  [4. Repay & Autopay] ◄─ [5. Hardship Safety Net] ◄─ [6. Credit Record]│
 │         │                       │                           │          │
 │         ▼                       ▼                           ▼          │
 │  Instalments on Due Day   Guardian Early Signals     Verifiable Ledger │
 │  Aging Buckets & Invoices Pauses / Term Shifts       Level 2 ($3k) Lift│
 └────────────────────────────────────────────────────────────────────────┘
```

### 1. Goal Savings
- Save toward a named goal ("mixer", "rent deposit", "emergency fund") starting from just **$5/month** with zero minimum balance barriers.
- Automated monthly collection via PayPal Subscriptions ($1-per-unit plan scaled by quantity).
- Freedom to top-up via PayPal Checkout, pause without penalty, or withdraw anytime back to PayPal/Venmo via PayPal Payouts.
- Visual timeline with streaks powered by Bryntum.

### 2. Savings-Linked Microloans
- **3+ months of steady saving** unlocks an uncollateralized, 0% interest microloan.
- **Graduated Loan Ladder:**
  - **Level 1:** Up to $1,500 (3–12 months term, 0% interest, $0 fees).
  - **Level 2:** Up to $3,000 (after on-time Level 1 repayment).
  - **Level 3:** Up to $5,000 (after on-time Level 2 repayment).
- **Deterministic Sizing Rule:** Monthly instalment cannot exceed the lesser of:
  - $2 \times$ the member's proven monthly saving amount.
  - $15\%$ of verified average monthly income.
- Instant disbursement directly to the member's PayPal or Venmo wallet via PayPal Payouts.

### 3. The Fair Loan Check
- **Consented Data Only:** Reads the member's Seed savings track record and past 6 months of PayPal/Venmo business income via PayPal Transaction Search.
- **Zero Discriminatory Proxies:** Absolutely no protected attributes (race, zip code, contacts, social media) are accessed or evaluated.
- **Numbers from Code, Words from AI:**
  - Deterministic engine calculates income average, lowest month, coefficient of variation, and sizing caps.
  - Astropods Underwriting Assistant generates a plain-language explanation card citing every figure and highlighting questions for human review.
- **Human Decision:** Loan officers review and approve; maker-checker countersigning enforced.

### 4. Repayment Schedule & Autopay
- Equal 0% instalments due on the member's chosen day (aligned with income cycles).
- 5-day grace period; zero late fees, zero penalties for early repayment.
- **Flexible Payment Methods:**
  - Default: Autopay via PayPal Subscriptions.
  - Invoicing: Recurring PayPal Invoicing series for members paying manually.
  - Catch-up / Pay-in-advance via one-click PayPal Payment Links.
- Daily aging job categorizes loans into buckets (Current, 1–7d, 8–30d, 31–60d, 60+d).

### 5. Hardship Safety Net
- Proactive Guardian Agent detects cashflow dips or income drop signals *before* due dates.
- Offers an agreed pre-set menu of options:
  - **Option A (Move Date):** Shift instalment up to 14 days later (auto-approved within policy).
  - **Option B (One-Month Pause):** Push instalment to the end of the term (officer + checker approval).
  - **Option C (Restructure):** Spread remaining balance over up to 6 additional months.
  - **Option D (Pay What You Can):** One-time partial payment; remainder stays on schedule.
- When approved, our schedule engine rewrites the plan, shifts the Bryntum timeline, pauses/reschedules the PayPal subscription, and keeps the member's standing intact.

### 6. Verifiable Credit Record
- Immutable history of saving consistency, on-time repayments, and completed loans, generated strictly from the double-entry ledger.
- **Fairness Principle:** Approved hardship pauses count as **"on plan"**, ensuring that asking for assistance never penalizes creditworthiness.
- **Verifiable Sharing:** One-click signed JSON credential with verifiable cryptographic signatures and shareable QR codes for landlords, credit unions, and community partners (via APIMatic SDK).
- Unlocks graduation to higher tiers (Level 2).

---

## Foundation: Core Banking & Architecture

To support Features 1 through 6 with bank-grade reliability, Seed implements a modular core banking layer:

### Double-Entry Ledger (`modules/ledger`)
- Append-only journal. Every dollar is tracked as balanced debit and credit entries.
- **Accounts:** PayPal Program Cash (Asset), Loans Receivable (Asset), Member Savings (Liability), Loss Reserve (Fund), Covered Losses (Expense).
- **ACID Transaction Rule:** A money state change, its journal entry, and its domain outbox event commit in a **single database transaction**.
- **Idempotency:** Unique source keys per PayPal event prevent duplicate postings.
- Nightly reconciliation against PayPal Transaction Search guarantees zero drift.

### Pure Computation Engines (`backend/engines/`)
- **Schedule Engine (`engines/schedule`)**: Pure Java library (`generate`, `allocate`, `age`, `reschedule`) tested with jqwik property-based tests. Zero IO, deterministic, sub-5 ms execution.
- **Rules Engine (`engines/rules`)**: Eligibility ladder, sizing rules, hardship boundaries, approval tiers.
- **Assessment Engine (`engines/assessment`)**: Fair-check math (average income, volatility CV, savings consistency).

### Runtime Roles (Single JAR)
The backend deployable (`seed-app`) runs in three distinct operational profiles:
- `api`: Handles REST/SSE traffic for member and desk applications.
- `ingress`: Fast, resilient PayPal webhook verification and inbox queuing.
- `worker`: Outbox relay, sagas, daily close loan aging, and scheduled tasks.

---

## The Demo Moment: Ana's Journey

1. **Save:** Ana, a home baker with no credit score, saves $75/month for 4 months inside PayPal.
2. **Unlock:** Steady savings unlock Level 1 eligibility (up to $1,500).
3. **Check:** Ana applies for $1,200 for a commercial mixer. The Fair Loan Check verifies her savings streak and Venmo sales, recommending $150/month over 8 months with clear reasons.
4. **Disburse:** Two loan officers countersign; PayPal Payouts dispatches $1,200 directly into her Venmo wallet in seconds.
5. **Protect:** In Month 3, her sales drop. The Guardian Agent alerts her and offers a 1-month pause. She confirms via SMS; officers approve; the schedule and Bryntum timeline slide seamlessly with no late fees.
6. **Graduate:** Ana completes repayment. Her cryptographically signed Credit Record updates automatically, unlocking Level 2 ($3,000).

---

## PayPal Integration Surface

| Capability | API / SDK | Feature | Role in PayPal Seed |
| :--- | :--- | :--- | :--- |
| **Subscriptions** | Billing Plans & Subscriptions | F1, F4, F5 | Monthly savings deposit collection; loan autopay; pause/suspend on hardship. |
| **Payouts** | Payouts API (PayPal & Venmo) | F1, F2 | Instant loan disbursements and savings withdrawals with 30-day idempotency. |
| **Invoicing** | Invoicing API | F4, F5 | Recurring invoice series for manual instalments and receipts. |
| **Checkout** | JS SDK v6 & Orders v2 | F1, F4 | Ad-hoc savings top-ups and catch-up loan repayments. |
| **Payment Links** | Payment Links API | F4, F5 | Instant SMS-delivered links for easy catch-up payments. |
| **Transaction Search** | Transaction Search API | F3, Core | Consented income verification; nightly ledger-to-PayPal cash reconciliation. |
| **Webhooks** | Webhooks API | All | Signature-verified asynchronous notifications driving ledger journal postings. |

---

## Sponsor Integrations

- **AG Grid (AG Studio)**: Powers the loan officer desk, application queues, maker-checker approvals, portfolio late buckets, and the Desk Analyst framework.
- **Bryntum (Scheduler Pro)**: Interactive visual timeline displaying savings milestones, loan instalments, and dynamic schedule shifts during hardship pauses.
- **Render**: Unified infrastructure deployment running `seed-api`, `seed-ingress`, `seed-worker`, Postgres, and Key Value.
- **Astropods**: Agent runtime executing the Underwriting Assistant, Guardian Agent, and evaluation suites.
- **Elastic**: Ingestion and ES|QL querying for fraud signals, velocity limits, and system audit trails.
- **Zapier**: SMS notifications for payment reminders, income dip warnings, and hardship approvals.
- **Postman**: API collection, contract tests, and chaos testing via Postman Simulator.
- **APIMatic**: Context integration for PayPal APIs and generation of the public Partner Verification SDK.

*(Note: KERNEL and Channel3 are deliberately omitted as their capabilities do not align with microfinance core banking).*

---

## Repository Structure

```text
paypal-seed/
├── backend/                  # Java 21, Spring Boot 3, Gradle Kotlin DSL (Modular Monolith)
│   ├── app/                  # Bootable app with role selection (api | ingress | worker)
│   ├── platform/             # Kernel, persistence, messaging outbox, security, testing
│   ├── engines/              # Pure Java zero-IO engines (schedule, rules, assessment)
│   ├── modules/              # Bounded context modules with schema-per-module (savings, lending, ledger...)
│   └── architecture-tests/   # Spring Modulith and ArchUnit architectural guardrails
├── web/                      # Turborepo + pnpm Next.js apps & shared packages
│   ├── apps/member/          # Next.js App Router: Member Seed tab inside PayPal/Venmo
│   ├── apps/desk/            # Next.js App Router: Loan Officer Desk (AG Grid)
│   └── packages/             # Shared UI, api-client, timeline, config, i18n
├── agents/                   # Astropods agent blueprints, system prompts, and eval datasets
├── contracts/                # OpenAPI specs (seed-api, internal-jobs) and AsyncAPI domain events
├── workflows/                # Render Workflows thin triggers for daily close and batch operations
├── infra/                    # Render blueprint, docker-compose.yml, Elastic templates
├── tests/                    # Playwright E2E suites and k6 load test scenarios
├── scripts/                  # Sandbox seed scripts and demo business clock controls
└── docs/                     # STRUCTURE.md and Architectural Decision Records (ADRs)
```

---

## Getting Started

### Prerequisites
- **Java 21 (LTS)** & Gradle
- **Node.js 20+** & **pnpm 9+**
- **Docker** (for local Postgres, Redis, and Elastic)

### 1. Start Infrastructure
```bash
docker compose -f infra/docker-compose.yml up -d
```

### 2. Run Backend
```bash
cd backend
./gradlew :app:bootRun
```
*To run in specific roles: set `SEED_ROLE=api`, `SEED_ROLE=ingress`, or `SEED_ROLE=worker`.*

### 3. Run Web Applications
```bash
cd web
pnpm install
pnpm dev
```
- Member Portal: `http://localhost:3000`
- Loan Officer Desk: `http://localhost:3001`

---

## Regulatory & Custody Model

Lending and deposit holding are regulated financial activities. PayPal Seed is designed to be operated by **PayPal in partnership with a licensed lending institution and banking partner**:
- In production, savings deposits are custodied by PayPal's FDIC-insured banking partner; 0% loans are originated through the licensed partner.
- In this hackathon demonstration, all financial interactions operate safely within the **PayPal Developer Sandbox**.

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
