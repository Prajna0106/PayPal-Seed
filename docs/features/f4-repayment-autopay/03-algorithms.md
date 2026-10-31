# F4 — Algorithms

Everything in this document is a **pure function** in `backend/engines/schedule` (plain Java 21, no Spring, no IO). The functions are deterministic, so they can be property-tested and replayed. The `lending` module only loads state, calls these functions and stores the results.

```java
// engines/schedule public surface (sketch, not final code)
Schedule        generate(GenerateRequest req, RepaymentPolicy policy);
Allocation      allocate(Schedule s, List<MoneyEvent> eventsSortedByDateThenId);
Aging           age(Schedule s, Allocation a, LocalDate asOf, RepaymentPolicy p);
Set<Timer>      timers(LoanSnapshot l, LocalDate asOf, boolean intraday, RepaymentPolicy p);
LocalDate       retryDate(LocalDate due, LocalDate failedOn, IncomeProfile prof, RepaymentPolicy p);   // Optional
MandateDesired  desiredMandate(LoanSnapshot l, MandateObserved o, LocalDate asOf);
List<MandateCmd> plan(MandateDesired d, MandateObserved o);
Schedule        moveDate(Schedule s, Allocation a, int seq, int days);
Schedule        deferToEnd(Schedule s, Allocation a, int seq);
Schedule        reamortize(Schedule s, Allocation a, int extraMonths, LocalDate asOf, RepaymentPolicy p);
DueDaySuggestion suggestDueDay(IncomeProfile prof, Integer statedPayday);
```

## 1. Money and dates

- **Money is `long` cents** inside the engine. `platform/kernel/Money` gets a `long cents` representation and USD only (the current `BigDecimal` record is replaced). This avoids scale bugs, and every value fits comfortably.
- **Loan amounts are whole dollars.** F2 offers principals in whole dollars. F4 accepts payments in whole dollars only. So every instalment, remaining amount and outstanding balance is a multiple of 100 cents. This is what lets one `$1 × quantity` PayPal plan collect any instalment exactly ([04 §2](04-paypal-integration.md#2-the-loan-autopay-plan)).
- **Business date** is a `LocalDate` in `America/Los_Angeles`, read from `BusinessClock` (the demo clock in the `demo` profile). PayPal timestamps (UTC) are converted with `effectiveDate = received_at.atZone(LA).toLocalDate()`.
- **Day counts** use `ChronoUnit.DAYS.between` on `LocalDate`, so daylight saving time cannot shift a due date.

## 2. Generate equal whole-dollar instalments

```text
input:  P = principal in dollars (integer), n = number of instalments (P ≥ n)
base = ⌊P / n⌋,  r = P mod n
amount(k) = base + (k > n − r ? 1 : 0)        for k = 1..n      // remainderPlacement = BACK
amount(k) = base + (k ≤ r     ? 1 : 0)                          // remainderPlacement = FRONT
```

**Why this split.** Any split of P into n whole-dollar parts has a largest part ≥ ⌈P/n⌉. This split reaches exactly ⌈P/n⌉, so it **minimises the largest instalment**. That is the quantity the F2 sizing rule caps (`instalment ≤ min(2 × saving, 15% × income)`). Putting the whole remainder on the last instalment, as plan.md proposed, gives a last instalment up to `base + n − 1`. For $1,000 over 7 months that is $148 against $143 here.

| P, n | Result (BACK) |
| --- | --- |
| $1,200, 8 | 150 × 8 |
| $1,000, 3 | 333, 333, 334 |
| $1,000, 7 | 142, 143, 143, 143, 143, 143, 143 |
| $500, 12 | 41 × 4, 42 × 8 |

**Remainder placement.** The default is `BACK` (plan.md: "rounding at the end"). If spike S3 shows that *raising* a subscription's quantity needs the payer to approve again, switch the policy to `FRONT`. With `FRONT`, quantities only ever go down over the loan's life, and so do hardship changes (C lowers the amount; A and B do not change it). Then autopay never needs re-approval. This is a policy-version switch, not a code change.

## 3. Due dates

```text
dateOf(ym, d) = ym.atDay(min(d, ym.lengthOfMonth()))
firstMonth   = smallest YearMonth m ≥ YearMonth(disbursedOn) with dateOf(m, dueDay) ≥ disbursedOn + minFirstDueLeadDays
due(k)       = dateOf(firstMonth.plusMonths(k − 1), dueDay)
```

Each date comes from the **anchor month plus k**, never from the previous due date. So `dueDay = 31` gives 31 Jan, 28 Feb (29 in a leap year), 31 Mar. A chained approach would drift to 28 Mar and stay there. Weekends are not rolled: the 5-day grace period absorbs weekends and PayPal's processing day.

## 4. Income-aware due-day suggestion

Input: the F3 consented income summary, which holds daily income totals for the last 6 months (no counterparties). At least 3 complete months are needed.

```text
for each candidate day d in 1..28:
    score(d) = mean over complete months m of  Σ income on dates in [dateOf(m,d) − 7, dateOf(m,d) − 1]
d*        = argmax score(d)            (ties → smaller d)
strength  = score(d*) / meanMonthlyIncome
if months < 3 or strength < 0.35:  fall back to statedPayday + 1, else 1
reason    = "On average ${score(d*)} of your income arrives in the week before the {d*}."
```

- **Wrap-around is free.** The window is on real dates, so for d = 2 it covers the last days of the previous month.
- **Why not a circular mean of income days?** Biweekly pay (for example the 1st and 15th) is bimodal. The mean lands on the 8th, when no money arrives. The window score picks a real peak.
- Days 29–31 are never suggested, so the member never gets a due day that changes with month length.
- Cost: 28 × months × 7 → under 1,500 additions.

## 5. Allocation waterfall (a fold)

Allocation is not stored as decisions. It is **recomputed** from the current schedule version and the full, ordered list of money events. Reschedules, reversals and out-of-order webhooks are all handled by re-running the fold ([ADR 0004](../../adr/0004-immutable-schedule-versions-derived-status.md), [ADR 0006](../../adr/0006-repayment-allocation-waterfall.md)).

```text
events = repayments with net = gross − Σ(reversals and customer refunds of that payment), sorted by (effectiveDate, id)
         + overpayment refunds (reduce the overpayment pool only)
paid[i] = 0, fullyPaidOn[i] = null, overpay = 0

for e in events:
  if e is OverpaymentRefund: overpay −= e.amount; continue        // validated at command time: never below 0
  a = e.net
  // Phase 1 — money that is due: instalments due on or before e.date + 7 days, oldest first
  for i in instalments by (dueDate, seq) where dueDate ≤ e.date + 7:
      take = min(a, amount[i] − paid[i]); paid[i] += take; a −= take
      if paid[i] == amount[i] and fullyPaidOn[i] == null: fullyPaidOn[i] = e.date
  // Phase 2 — prepayment: shorten the loan from the end (inverse order of maturity)
  for i in instalments by (dueDate desc, seq desc):
      take = min(a, amount[i] − paid[i]); …same as above…
  overpay += a
return Allocation(paid[], fullyPaidOn[], overpay, outstanding = Σ(amount − paid))
```

**Why a 7-day window in phase 1.** PayPal can charge a day early (timezone), and members often pay "this month's bill" a few days before it is due. Those payments must settle the upcoming instalment, not shorten the loan.

**Why inverse maturity for the excess.** If excess money paid the *next* instalment, the next autopay charge would find nothing due and over-collect again, month after month. Shortening from the end keeps every future autopay amount unchanged, and the loan simply finishes early.

**Properties (tested in [06](06-test-plan.md)):** `Σ paid + overpay = Σ net`; `0 ≤ paid[i] ≤ amount[i]`; adding a payment never increases `outstanding`; the fold is deterministic; `outstanding` equals the ledger receivable.

Cost: O((n + m) · n) with n ≤ 36 instalments and m ≤ ~100 events. That is microseconds.

## 6. Aging and late buckets

```text
oldest      = first instalment by (dueDate, seq) with paid < amount
dpd         = oldest == null ? 0 : max(0, DAYS(oldest.dueDate, asOf))
bucket(dpd) = 0 → CURRENT | 1..7 → DPD_1_7 | 8..30 → DPD_8_30 | 31..60 → DPD_31_60 | ≥61 → DPD_60_PLUS
inGrace     = 1 ≤ dpd ≤ graceDays (5);   late = dpd > graceDays
overdue     = Σ (amount − paid) for instalments with dueDate < asOf
```

- **Due today is not past due** (dpd = 0 on the due date).
- **The current version is what counts.** After an approved pause, dpd is measured against the new dates. That is how "asking for help keeps you on plan" works, with no special cases.
- **Facts stay facts.** When an instalment reaches dpd 6 unpaid, `InstalmentMissed` is written once. A reschedule approved later changes the plan from then on but does not delete the fact (INV-9). F6 reads these facts.
- **Pending payments.** If a `PENDING` sale covers the overdue amount, the bucket is still computed truthfully, but the snapshot carries `pendingCover = true`. Engagement and hardship then hold off.

## 7. Close of business (COB)

### 7.1 Timers: when can this loan's state change next?

State changes on known dates only. So for each loan we compute the next dates and store them as durable timers ([ADR 0007](../../adr/0007-business-date-cob-durable-timers.md)).

```text
timers(loan, asOf, intraday):
  if loan.status != ACTIVE: return ∅
  T = ∅;  after = intraday ? asOf − 1 : asOf       // intraday changes may schedule for today
  for each instalment i with remaining > 0:
     add REMIND_DUE_SOON(i)  at i.due − 3          if > after
     add CREATE_INVOICE(i)   at i.due − 7          if mode == INVOICE and no open invoice, clamp to ≥ after + 1
     add WATCHDOG(i)         at i.due + 1          if mode == AUTOPAY
     add GRACE_NUDGE(i)      at i.due + 3
     add GRACE_EXPIRY(i)     at i.due + 6
  o = oldest unpaid
  for b in {1, 8, 31, 61}: add BUCKET_BOUNDARY(dpd:b) at o.due + b   if > after
  for each PLANNED retry attempt: add RETRY_CAPTURE(attempt) at planned_on
  return T
```

After **every** change to a loan (repayment, adjustment, reschedule, mode change, COB step), and inside the same transaction: `DELETE FROM loan_timer WHERE loan_id = ?`, then insert `timers(...)`. Timers are a pure function of state, so rebuilding them is always correct and idempotent.

### 7.2 COB for business date D

```text
start(D):   require cob_run(D − 1) ∈ {COMPLETED, DEGRADED}   (or D is the first date)
            INSERT cob_run(D, RUNNING, S shards) ON CONFLICT DO NOTHING   → return existing on conflict
shard(D,s): loop
              ids = SELECT DISTINCT loan_id FROM loan_timer
                    WHERE fire_on ≤ D AND shard = s AND loan_id > cursor ORDER BY loan_id LIMIT 500
              if empty → shard DONE
              for each id (virtual threads, at most 16 at a time):
                 TX:
                   lock loan  FOR UPDATE SKIP LOCKED      (locked → add to the retry list, continue)
                   snap  = load(loan, schedule, allocation)
                   aging = age(snap, D)
                   if aging.bucket ≠ loan.bucket → bucket_transition + LateBucketChanged(cause=COB)
                   for t in due timers ordered by (fire_on, kind):
                      GRACE_EXPIRY    → INSERT instalment_missed ON CONFLICT DO NOTHING → InstalmentMissed
                      REMIND_DUE_SOON → if still unpaid → InstalmentDueSoon
                      GRACE_NUDGE     → if still unpaid and no pendingCover → GraceNudgeDue
                      CREATE_INVOICE  → payments.createInvoice(key = "inv:{loan}:v{ver}:s{seq}")
                      WATCHDOG        → payments.checkMandateTransactions(key = "wd:{loan}:{D}")
                      RETRY_CAPTURE   → re-check (still unpaid, no hardship case, no pending) → payments.retryCapture
                   rebuild timers(snap, D, intraday=false)          // fired timers disappear in the same TX
                 save cursor every batch
finish(D):  re-run the skipped loans (blocking lock); status = failed > 0 ? DEGRADED : COMPLETED; CobCompleted
```

- **Exactly-once effect.** Fired timers are deleted in the same transaction that applies their effects. A crash before commit re-fires them; a crash after commit cannot. Every outbound command also has a deterministic idempotency key.
- **Intraday sweeper.** When a change during day D creates a timer for D (for example, a reschedule makes a reminder due today), the worker's sweeper (every 15 minutes) fires timers with `fire_on ≤ D` once COB(D) is complete. Dedupe keys make double firing harmless.
- **Sharding.** `shard = abs(hashtext(loan_id)) % S` is stored on the timer row. S = 8 by default. Render Workflows runs the shards in parallel, with retries per shard.
- **Catch-up.** The demo clock moving forward 30 days runs COB D+1 … D+30 in order. Each run is quick because timers are sparse.
- **Oracle and fallback.** `scan(D)` computes the same effects by brute force for every active loan, without timers. `seed.cob.mode = scan` switches to it. The differential test (TC-COB-07) runs both for a simulated year and requires identical events.

**Cost.** Each loan has about 5 timers per instalment month, so about 5 timer firings per loan per month. For 100k loans that is about 17k loan-transactions on an average day. At about 3 ms each across 8 shards × 16 workers, that is under 1 second of wall time per 1,000 loans. Worst case (every loan has a timer the same day): 100k × 3 ms / 128 ≈ 2.5 minutes, well inside the 15-minute target.

## 8. Income-aware retry of a failed autopay

```text
retryDate(due, failedOn, profile):
  window = [max(failedOn + 2, due + 2), due + 4]          // ≥ 48 h after failure; result known before grace ends (dpd 5)
  if window is empty → none
  if profile missing or weak → due + 3 (if inside the window)
  pick t in window maximising expected(t) = mean income on day-of-month (t − 1);  ties → earliest
  // retry the day AFTER money usually lands, so it has cleared by the time PayPal charges
```

At most one automatic retry (`maxAutoRetries = 1`). It is cancelled if the money arrives another way, a hardship case opens, or a pending payment exists. After that, the member gets a Payment Link and the hardship options, never a second automatic charge. This is a deliberate choice: repeated retries against an empty balance can trigger overdraft fees at the member's bank.

## 9. Mandate reconciler (desired state)

PayPal's subscription is an **actuator**. The reconciler makes it match the schedule ([ADR 0005](../../adr/0005-paypal-mandate-desired-state-reconciliation.md)).

```text
desiredMandate(loan, observed, asOf):
  if loan.status ≠ ACTIVE or loan.mode ≠ AUTOPAY or loan.outstanding == 0 → CANCELLED
  nb     = observed.nextBillingDate (business tz)
  target = instalment with remaining > 0 and |target.due − nb| ≤ 3 days        // the one PayPal is about to charge
  if target == null → SUSPENDED   (e.g. a paused month: nothing is due on this cycle)
                      reason = NO_INSTALMENT_THIS_CYCLE; resume after nb
  else             → ACTIVE, quantity = target.remaining / 100
  (overdue instalments from earlier cycles are never added to the quantity;
   they are collected by the single retry or by Payment Link → no surprise double charges)

plan(desired, observed):                  // fewest commands; order matters
  CANCELLED and observed live   → [CANCEL]
  SUSPENDED and observed ACTIVE → [SUSPEND]
  ACTIVE:
     observed SUSPENDED          → [ACTIVATE]
     quantity differs            → [REVISE(quantity)]
  |observed.nextBilling − expected due| > 3 days → emit MandateDriftDetected; this cycle → Payment Link
  equal                         → []      (convergence: plan(desired, apply(plan)) == [])
```

It runs after every repayment, adjustment, reschedule and mode change (as an outbox-driven command), and once a day for all live mandates. `desired_hash` lets the daily pass skip mandates whose desired state has not changed since the last successful observe.

## 10. Reschedule primitives (called by F5)

All three return a **new version** and keep Σ = principal. Re-folding the existing money history on the new version must land money where it was. The property test PROP-RSC-01 found three ways a naive version breaks that. The fix is to split each instalment's paid amount in two:
- **Money paid as due money** (waterfall step 1) stays on the instalment's original date.
- **Money paid in advance** (step 2, tracked per instalment as `paidInAdvanceCents`) is gathered into one fully prepaid instalment at the very end. The in-advance rule fills from the last instalment backwards, so it refills exactly that tail.

```text
moveDate(s, a, seq, days, asOf):      // option A
  require 1 <= days <= 14, instalment still owed, new date after today and before the next instalment
  the part already paid as due money stays on the old date; what is still owed moves to the new date

deferToEnd(s, a, seq, asOf):          // option B (one-month pause)
  what is still owed on seq moves to the month after the last instalment still owed,
  and never to a date on or before today; other instalments keep (amount - paid in advance)

reamortize(s, a, k, asOf):            // option C (smaller, longer plan)
  require 1 <= k <= 6
  instalments keep only what was paid on them as due money
  count = min(unpaid + k, floor(outstanding / $25)); refuse if count <= unpaid (no benefit)
  split(outstanding, count) from the first due day after today and after every kept instalment

all three: append one prepaid tail instalment = sum of paidInAdvance, in the month after the latest row
```

What the property test caught before these rules:
1. A pause after a large prepayment silently moved the prepaid money onto the deferred amount. That re-opened an instalment the member had already paid.
2. A pause of a long-overdue instalment landed in the past, so it stayed overdue.
3. A date move pushed a partly paid instalment out of its payment's due window and turned that money into prepayment.

Option D (pay what you can) is not a reschedule. It is just a payment through FR-05, and the waterfall does the rest.

## 11. Payoff and closure

`payoffQuote = outstanding = Σ (amount − paid)`. At 0% there is no per-diem, so the quote is valid until the next money event. A payoff payment runs through the same waterfall: phase 1 clears due instalments, phase 2 clears the rest from the end. Outstanding reaches 0 → `REPAID` → reconciler desired = CANCELLED → `LoanRepaid`.

## 12. Complexity summary

| Function | Time | Budget |
| --- | --- | --- |
| `generate` | O(n) | < 0.1 ms |
| `allocate` | O((n + m) · n) | < 1 ms at n = 36, m = 100 |
| `age` | O(n) | < 0.1 ms |
| `timers` | O(n) | < 0.1 ms |
| `suggestDueDay` | O(28 · months · 7) | < 0.5 ms |
| `desiredMandate` + `plan` | O(n) | < 0.1 ms |
| COB per loan (DB round trips dominate) | about 3 ms | 100k loans in under 3 minutes worst case on 8 × 16 workers |
