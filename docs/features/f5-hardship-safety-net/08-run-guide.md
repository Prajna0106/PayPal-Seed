# F5 — Run Guide

How to run the hardship safety net locally, with or without a model key, and how to walk Ana's story end to end with the real endpoints. Every path and body below matches the controllers in `backend/` and the contracts in `contracts/openapi/`.

## 1. Model access (OpenRouter)

The Guardian reaches models only through the agent gateway, which reads these settings (`backend/app/src/main/resources/application.yml`, block `seed.llm`):

| Environment variable | Property | Default | Meaning |
| --- | --- | --- | --- |
| `OPENROUTER_API_KEY` | `seed.llm.api-key` | empty | The OpenRouter key. Empty means no model is ever called |
| `SEED_LLM_ENABLED` | `seed.llm.enabled` | `true` | Set `false` to disable models even with a key |
| `OPENROUTER_BASE_URL` | `seed.llm.base-url` | `https://openrouter.ai/api/v1` | OpenAI-compatible endpoint |
| `SEED_LLM_MODELS` | `seed.llm.models` | `openai/gpt-4o-mini,anthropic/claude-3.5-haiku` | Fallback chain sent as OpenRouter `models`. A placeholder until spike SH3 confirms the choice |
| `SEED_LLM_DAILY_BUDGET_MICRO_USD` | `seed.llm.daily-budget-micro-usd` | `20000000` ($20) | Spending it trips the kill switch to `TEMPLATES_ONLY` |
| `SEED_LLM_MAX_CONCURRENT` | `seed.llm.max-concurrent` | `8` | Bulkhead: concurrent model calls per process |
| `SEED_LLM_CALL_TIMEOUT` | `seed.llm.call-timeout` | `20s` | Per call; counts toward the circuit breaker |
| `SEED_AGENT_DEFAULT_MODE` | `seed.llm.default-mode` | `LIVE` | `LIVE`, `SHADOW` or `TEMPLATES_ONLY` when no desk change was made |

Every request asks OpenRouter not to collect data (`provider.data_collection: deny`, `require_parameters: true`), uses `temperature: 0`, and sends a strict JSON schema for structured answers. Member names, emails, phone numbers and PayPal ids never leave the process: the gateway redacts them and the tools return a pseudonymous `memberRef`.

### Without a key (the default)

The safety net works in full. The difference:

- The Guardian finds no model, reports the run as failed, and the case uses the deterministic **template** paragraph (`source: TEMPLATE`).
- A member's free-text reason is classified by the keyword fallback instead of a model.
- No borderline `WATCH` case is escalated, because only the AI second opinion can raise a level, and only by one step.

Detection, every number, the options, the member's choice, the approvals and the schedule change are code and people, so nothing else changes. The integration tests run this way.

### With a key

```powershell
$env:OPENROUTER_API_KEY = "<your key>"        # never commit it, never paste it into a file in the repo
$env:SEED_LLM_DAILY_BUDGET_MICRO_USD = "5000000"   # $5 for a demo day
```

Also set a spending limit on the key itself in the OpenRouter dashboard. Use one key per environment (dev and demo), so one can be revoked without breaking the other. On Render, add the key as a secret environment variable of the worker service.

When a case opens, the Guardian gets a scoped token (one loan, one case, 10 minutes), calls at most 6 read tools within 45 seconds, writes the officer brief and the member paragraph (English and Spanish) with a figures list, and submits a proposal. Governance runs the validator (checks V01 to V12). An approvable proposal puts the AI paragraph in the outreach; anything else keeps the template. The outreach goes out when the outcome arrives or when the agent deadline passes (`seed.hardship.agent-deadline`, default 90 seconds), whichever comes first, exactly once.

## 2. Kill switch

Modes, from least to most restrictive: `LIVE` (AI text is used), `SHADOW` (the Guardian runs and its text is stored as `AI_SHADOW`, but members and officers see templates), `TEMPLATES_ONLY` (no model call at all).

```powershell
$staff = @{ "X-Seed-Staff-Id" = [guid]::NewGuid().ToString(); "X-Seed-Staff-Roles" = "OFFICER" }
Invoke-RestMethod http://localhost:8080/api/v1/desk/agents/kill-switch -Headers $staff

# Any officer can make agents safer at once:
Invoke-RestMethod http://localhost:8080/api/v1/desk/agents/kill-switch -Method Post -Headers $staff `
  -ContentType "application/json" -Body '{"scope":"GLOBAL","mode":"TEMPLATES_ONLY","reason":"Demo: show the template path"}'

# Only an admin can move back to a less restrictive mode:
$admin = @{ "X-Seed-Staff-Id" = [guid]::NewGuid().ToString(); "X-Seed-Staff-Roles" = "ADMIN" }
Invoke-RestMethod http://localhost:8080/api/v1/desk/agents/kill-switch -Method Post -Headers $admin `
  -ContentType "application/json" -Body '{"scope":"GLOBAL","mode":"LIVE","reason":"Back to live after review"}'
```

`scope` is `GLOBAL` or an agent name (`guardian`). The switch also trips itself to `TEMPLATES_ONLY` when the validator rejects more than 20% of the last 50 proposals (judged after at least 10), when more than 3 grounding failures happen in an hour, or when the daily budget is spent. Automatic trips never move a mode up.

## 3. Start the backend for the demo

Prerequisites: Java 21 and a PostgreSQL 16 (or 17) database. From `backend/`:

```powershell
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/paypalseed"
$env:DATABASE_USER = "seed"
$env:DATABASE_PASSWORD = "seed_password"
$env:SEED_ROLE = "worker"                       # one process: web routes plus the scan and sweep schedulers
$env:SPRING_PROFILES_ACTIVE = "demo,dev"        # shared demo clock, dev headers, local internal token
$env:ZAPIER_SIGNING_SECRET = "local-zapier-secret"   # only needed for the SMS reply path
.\gradlew.bat :app:bootRun
```

The `dev` profile accepts `Authorization: Bearer local-dev-internal-token` for `/internal/**` and the dev identity headers `X-Seed-Member-Id`, `X-Seed-Staff-Id` and `X-Seed-Staff-Roles`. Never enable `seed.security.dev-header-auth` outside dev, test and demo: those headers let a caller choose any identity. Without `ZAPIER_SMS_HOOK_URL`, outbound SMS are written to the log as `[dry-run SMS]`.

To test with a single officer, start with `--seed.hardship.checker-required=false` (options B and C then need one approval). The default is `true`.

## 4. Walk Ana's story

Ana borrows $1,200 over 8 months, due on the 20th. Her weekly income was $200 for 9 weeks, then $96 for 4 weeks: a persistent 52% drop. The scorecard gives 7 points (`INCOME_DROP_SEVERE` 5 + `LOW_COVERAGE` 2), which is `LIKELY_MISS`, and the ranker suggests option C.

```powershell
$base = "http://localhost:8080"
$svc  = @{ Authorization = "Bearer local-dev-internal-token" }
$ana  = [guid]::NewGuid().ToString()
$member = @{ "X-Seed-Member-Id" = $ana }

# 1. Register the disbursed loan on today's business date.
$loan = Invoke-RestMethod "$base/internal/loans/disbursed" -Method Post -Headers $svc -ContentType "application/json" -Body (@{
    memberId = $ana; principalCents = 120000; instalmentCount = 8; dueDay = 20
    disbursedOn = (Get-Date -Format "yyyy-MM-dd"); disbursementRef = "demo-ana-$([guid]::NewGuid())" } | ConvertTo-Json)
$schedule0 = Invoke-RestMethod "$base/api/v1/loans/$($loan.loanId)/schedule" -Headers $member
$today    = [datetime]$schedule0.businessDate
$firstDue = [datetime]$schedule0.instalments[0].dueDate
```

If the demo clock is not on the wall-clock date, use `$schedule0.businessDate` as `disbursedOn` instead (register again with a new `disbursementRef`).

```powershell
# 2. Move the shared business date to 8 days before the first due date.
$days = ($firstDue.AddDays(-8) - $today).Days
if ($days -ge 1) { Invoke-RestMethod "$base/internal/demo-clock/advance?days=$days" -Method Post -Headers $svc }
$scanDate = $firstDue.AddDays(-8)

# 3. Seed 13 weeks of consented income ending the day before the scan date.
$weeks = 0..12 | ForEach-Object {
    @{ weekStart = $scanDate.AddDays(-91 + 7 * $_).ToString("yyyy-MM-dd"); inflowCents = $(if ($_ -lt 9) { 20000 } else { 9600 }) } }
Invoke-RestMethod "$base/internal/underwriting/members/$ana/income" -Method Put -Headers $svc -ContentType "application/json" `
  -Body (@{ consent = $true; weeks = $weeks } | ConvertTo-Json -Depth 4)

# 4. Run the scan (the worker also runs it daily at 06:15 Los Angeles time).
Invoke-RestMethod "$base/internal/jobs/hardship/scan" -Method Post -Headers $svc -ContentType "application/json" `
  -Body '{"shardGroup":0,"shardCount":1,"limit":500}'

# 5. Release the outreach. With a key, the Guardian usually answers within seconds; without one, the agent
#    deadline (90 s) releases the template. The worker sweeps every 30 s; this forces it now.
Invoke-RestMethod "$base/internal/jobs/hardship/sweep" -Method Post -Headers $svc
```

The log now shows the dry-run SMS with options A to D and "Reply A, B, C or D".

```powershell
# 6. Ana opens the offer in the app (status becomes OFFER_VIEWED). Add ?lang=es for Spanish.
$view = Invoke-RestMethod "$base/api/v1/loans/$($loan.loanId)/hardship" -Headers $member
$view.offer.options | Format-Table label, primitive, tier, recommended, sustainable, warnings
$view.offer.message                     # source AI or TEMPLATE

# 7. She chooses B (pause one month). C is the code's suggestion; her choice is final.
$b = $view.offer.options | Where-Object label -eq "B"
$choice = Invoke-RestMethod "$base/api/v1/hardship/cases/$($view.caseId)/choice" -Method Post `
  -Headers ($member + @{ "Idempotency-Key" = "ana-choice-1" }) -ContentType "application/json" `
  -Body (@{ offerId = $view.offer.offerId; optionId = $b.optionId } | ConvertTo-Json)
$choice                                  # PENDING_APPROVAL, tier MAKER_CHECKER, decisionDueAt
```

Instead of steps 6 and 7, Ana can reply by SMS. Engagement needs her phone first; in the demo database:

```sql
INSERT INTO engagement.contact (member_id, phone_e164, locale, timezone)
VALUES ('<ana member id>', '+15550001001', 'en', 'America/Los_Angeles');
```

```powershell
$secret = "local-zapier-secret"                          # same value as ZAPIER_SIGNING_SECRET
$body = '{"from":"+15550001001","text":"B","messageId":"demo-sms-1"}'
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
$hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
$sig = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$ts.$body")) | ForEach-Object { $_.ToString("x2") })
Invoke-RestMethod "$base/api/v1/webhooks/zapier/sms" -Method Post -ContentType "application/json" -Body $body `
  -Headers @{ "X-Seed-Timestamp" = $ts; "X-Seed-Signature" = $sig }
```

The signature is lowercase hex HMAC-SHA256 over `timestamp.body`, and the timestamp must be fresh. Replaying the same `messageId` changes nothing.

```powershell
# 8. The desk: queue, evidence panel, then maker and checker (two different people).
$maker   = @{ "X-Seed-Staff-Id" = [guid]::NewGuid().ToString(); "X-Seed-Staff-Roles" = "OFFICER" }
$checker = @{ "X-Seed-Staff-Id" = [guid]::NewGuid().ToString(); "X-Seed-Staff-Roles" = "CHECKER" }
Invoke-RestMethod "$base/api/v1/desk/hardship/cases?status=PENDING_APPROVAL" -Headers $maker
Invoke-RestMethod "$base/api/v1/desk/hardship/cases/$($view.caseId)" -Headers $maker     # signals, options, narratives, approval

$decision = @{ decision = "APPROVE"; reasonCode = "INCOME_DROP_VERIFIED"; note = "Ana chose B; the desk accepts the pause."; evidenceViewedMs = 30000 } | ConvertTo-Json
Invoke-RestMethod "$base/api/v1/desk/hardship/cases/$($view.caseId)/decision" -Method Post `
  -Headers ($maker + @{ "Idempotency-Key" = "maker-1" }) -ContentType "application/json" -Body $decision     # role MAKER, next CHECKER
Invoke-RestMethod "$base/api/v1/desk/hardship/cases/$($view.caseId)/decision" -Method Post `
  -Headers ($checker + @{ "Idempotency-Key" = "checker-1" }) -ContentType "application/json" -Body $decision   # requestStatus APPROVED

# 9. The saga applied the change as schedule version 2; the previous plan is kept unchanged.
Invoke-RestMethod "$base/api/v1/loans/$($loan.loanId)/schedule/versions" -Headers $member
Invoke-RestMethod "$base/api/v1/desk/loans/$($loan.loanId)/integrity" -Headers $maker
```

Things worth showing:

- The maker deciding again as checker gets `403 sod-violation`.
- A payment between the offer and the decision changes what is owed, so the choice or decision returns `409 hardship-offer-stale` / `approval-stale` and Ana confirms the new numbers.
- Option A (move the date up to 14 days) is approved by policy as soon as Ana chooses it.
- With the kill switch at `TEMPLATES_ONLY`, the same story runs with template text and no model call.
- `POST /internal/jobs/governance/verify-audit-chain` (service token) recomputes the hash-chained audit log and reports `intact: true`.

## 5. Security notes

- Never commit `OPENROUTER_API_KEY`, `ZAPIER_SIGNING_SECRET` or `SEED_INTERNAL_TOKEN`. Keep them in your shell or an untracked env file, and as secret environment variables on Render.
- Put a spending limit on the OpenRouter key and keep `SEED_LLM_DAILY_BUDGET_MICRO_USD` low for demos.
- The demo uses the PayPal Sandbox only.
- The dev identity headers are for dev, test and demo profiles only.
