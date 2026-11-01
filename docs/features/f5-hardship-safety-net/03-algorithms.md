# F5 — Algorithms

Everything here that touches a number is a **pure function** in `backend/engines/rules` (plain Java 21, no Spring, no IO), as far as it can be. The `hardship` module loads state, calls these functions and stores the results. The AI never computes a number that reaches a member or an officer: it writes words around numbers that these functions produced, and a checker (section 11) proves it.

```java
// engines/rules public surface for F5 (sketch, not final code)
IncomeFeatures    extractFeatures(long[] weeklyInflowCents /*13, oldest first*/, long instalmentCents);
ScorecardResult   score(IncomeFeatures f, SignalInputs in, HardshipPolicy p);
Level             applyHysteresis(Level raw, Level previous, int belowCount);
Escalation        validateEscalation(ScorecardResult r, AiOpinion o);
OptionMetrics     metrics(OptionPreview preview, LocalDate asOf, long recentMonthlyCents, HardshipPolicy p);
Ranking           rank(List<OptionMetrics> options, Persistence persistence, boolean hasIncomeData);
PolicyVerdict     check(OptionKind kind, HardshipHistory history, LoanFacts loan, HardshipPolicy p);
ApprovalTier      tierFor(OptionKind kind);
String            snapshotHash(LoanId id, int baseVersion, OptionKind kind, Args args, List<SlotLine> proposed);
HoldDecision      holdFor(CaseFacts c, InstalmentFacts i, List<Hold> existing, LocalDate asOf, HardshipPolicy p);
Instant           slaDue(Instant start, boolean priority);
GroundingResult   ground(String narrative, Facts allowed, Locale locale);
RubberStampFlag   detect(List<Decision> lastDecisions);
```

`OptionPreview` is plain data (the proposed instalments with their remaining amounts) that `hardship` copies out of `LendingFacade.previewReschedule`. That keeps `engines/rules` independent of `engines/schedule`, and the ArchUnit rule TC-ARC-02 keeps both free of Spring and IO.

## 1. Conventions and policy v1

- Money is `long` cents. Dates are business dates (`LocalDate`, `America/Los_Angeles`). Day counts use `ChronoUnit.DAYS.between`. Percentages are integer basis points (bps) so there is no floating point in any decision.
- The policy is versioned like F4's `RepaymentPolicy`. A case records the `policy_version` and `scorecard_version` it was decided under, so a later change never rewrites an old decision.
- `lookback` = 13 consecutive 7-day buckets of inflow ending the day before the scan date (oldest first, `w[0]..w[12]`, 91 days). The last four buckets are "recent".

| Parameter | v1 value | Used in |
| --- | --- | --- |
| `scanLeadDays` | 10 | Scan window: an instalment due in the next 10 days |
| `graceDays` | 5 (F4) | Scan window includes instalments in grace (1 to 5 days past due) |
| `maxEligibleBucket` | not `DPD_60_PLUS` | Eligibility |
| `watchAt`, `likelyMissAt`, `imminentAt` | 3, 6, 9 points | Levels |
| `incomeDropModerateBps`, `incomeDropSevereBps` | 3,000 and 5,000 | S1 |
| `usableShareBps` | 3,000 (30% of recent monthly inflow can go to the loan before it is a squeeze) | S2 |
| `coverageLowBps`, `coverageCriticalBps` | 10,000 and 6,000 | S2 |
| `maxBurdenBps` | 2,500 (25%; F2 sizes new loans at 15%) | Sustainable test |
| `dBurdenBps` | 1,500 (the F2 sizing share) | Option D amount |
| `persistenceBelowBps`, `persistenceWeeks` | 7,000 and 3 of 4 | Persistent or transient drop |
| `minHistoryWeeks` | 8 weeks with any inflow | Whether income signals can fire |
| `hysteresisScans` | 2 | Level drops |
| `cooldownDays` | 14 after a case closes (not for the member's own `HELP`) | Eligibility |
| `outreachPerInstalment`, `reminderPerInstalment` | 1 and 1 (at due − 1) | Outreach limits |
| `outreachPer30Days` | 2 | Outreach limits |
| `holdWindowDays` | 3 (hold may be placed when the miss date is within 3 days) | Section 9 |
| `holdDays` | 3 COB dates, plus one audited 2-day extension | Section 9 |
| `slaBusinessHours`, `slaPriorityHours` | 10 and 4 | Section 10 |
| `offerExpiryDay` | due + 5 (the last day of grace) | Section 10 |

## 2. Feature extraction

Inputs: the 13-week series `w`, the target instalment amount `I` (the oldest unpaid instalment), and `asOf`.

```text
extractFeatures(w[0..12], I):
  active = count of buckets with w > 0
  if active < minHistoryWeeks or all zero: return NO_INCOME_DATA          // never raises or lowers risk
  baselineWeekly = median(w[0..12])                                         // 13 values, so the 7th smallest
  baseline4      = 4 * baselineWeekly
  last4          = w[9] + w[10] + w[11] + w[12]
  dropBps        = baseline4 == 0 ? 0 : max(0, (baseline4 - last4) * 10000 / baseline4)   // integer division
  recentMonthly  = last4 * 30 / 28                                         // last four weeks scaled to 30 days
  coverageBps    = recentMonthly * usableShareBps / I                      // = (usable share * recent monthly) / instalment, in bps
  burdenBps(x)   = x * 10000 / recentMonthly                               // share of recent monthly income an amount x takes
  weeksBelow     = count of i in 9..12 with w[i] * 10000 < persistenceBelowBps * baselineWeekly
  persistence    = weeksBelow >= persistenceWeeks ? PERSISTENT : TRANSIENT
```

Why the median over all 13 weeks: it resists a few bad weeks and a few spikes. When the last four weeks are very low, they pull the median slightly down, which makes the drop a little smaller than against an uncontaminated baseline. That errs on the side of fewer alerts, which suits a feature that contacts people about money.

**No data is not a risk.** A member with a short history, or one who has not consented to the income data, gets `NO_INCOME_DATA`. S1 and S2 do not fire and the option ranker offers no recommendation. The other signals can still fire from loan behaviour.

## 3. The scorecard

All inputs are the member's own consented data and their own loan behaviour. There is no location, contact, social or demographic input. Each rule is non-decreasing in its inputs, so more evidence of trouble never lowers the score (property PROP-H-01).

| Signal | Code | Rule | Points |
| --- | --- | --- | --- |
| S1 income drop | `INCOME_DROP_MODERATE`, `INCOME_DROP_SEVERE` | `dropBps >= 3000` → 3; `>= 5000` → 5 (the larger one only) | 3 or 5 |
| S2 coverage | `LOW_COVERAGE`, `CRITICAL_COVERAGE` | `coverageBps < 10000` → 2; `< 6000` → 4 (the larger only) | 2 or 4 |
| S3 failed autopay | `AUTOPAY_FAILED`, `AUTOPAY_FAILED_TWICE` | One failed charge in the last 14 days → 3; a second failure on the same instalment → 5 in total | 3 or 5 |
| S4 payday slip | `PAYDAY_SLIP` | The F3 timing profile's usual inflow day passed, with no inflow of at least half the baseline weekly amount, and 3 or more days have passed | 2 |
| S5 late history | `IN_GRACE`, `RECENT_LATE` | Currently 1 to 5 days past due → 3; otherwise a move to `DPD_1_7` or worse in the last 90 days → 2 (the larger only) | 3 or 2 |
| S6 savings stress | `SAVINGS_STRESS` | The F1 streak was paused, or a withdrawal left the balance below one instalment, in the last 30 days | 1 |
| S7 member asks | `MEMBER_REQUEST` | The member sent `HELP` or opened a case. Sets the level to IMMINENT directly | — |

Maximum points: 5 + 4 + 5 + 2 + 3 + 1 = 20.

```text
level(points) = points >= 9 ? IMMINENT : points >= 6 ? LIKELY_MISS : points >= 3 ? WATCH : NONE

score(f, in, policy):
  signals = []
  add S1..S6 per the table, each with evidence (the numbers that fired it)
  points  = sum(signals.points)
  raw     = in.memberRequested ? IMMINENT : level(points)
  return { points, raw, signals }
```

**Reason-code evidence shape** (stored in `hardship.signal.evidence` and shown to officers):

```json
{ "code": "INCOME_DROP_SEVERE", "points": 5,
  "evidence": { "last4Cents": 43200, "baseline4Cents": 90000, "dropBps": 5200, "weeksCompared": 13 } }
```

Every number the member or officer later reads is in an `evidence` object. That is what the grounding checker compares against.

### 3.1 Hysteresis and cool-down

```text
applyHysteresis(raw, previous, belowCount):
  if raw >= previous:           return (raw, 0)                  // rises at once, resets the counter
  belowCount = belowCount + 1                                     // raw is lower than the stored level
  if belowCount >= hysteresisScans (2): return (raw, 0)          // two consecutive scans below: drop
  return (previous, belowCount)                                   // hold the old level
```

`previous` and `belowCount` come from the loan's last persisted `scorecard_run` for the same instalment cycle (`features.hysteresis`). A new instalment cycle starts at NONE. Because the scan runs only in the window (10 days before due, or in grace), the two scans are two consecutive scan dates.

A case opens at LIKELY_MISS or above. WATCH is a signal and a desk column, never an outreach.

**Suppression**, checked before any outreach is created. No case or message is made when any of these hold:

- the loan has an open case (the unique index also enforces it);
- the target instalment is fully paid, or a `PENDING` PayPal payment covers it (F4 AC5, `pendingCents`);
- the case closed less than 14 days ago (the member's own `HELP` is never blocked);
- the member opted out of every channel (SMS and in-app);
- engagement already sent the maximum: one outreach and one reminder per instalment, two outreaches per 30 days.

Complexity: O(13) for features and O(1) for the rest.

## 4. AI second opinion (escalate-only)

The deterministic level is the floor. The model may only ask for one step more, only when the score is borderline, and only by pointing at signals the code produced.

```text
borderline(points) = points in {2, 5, 8}          // exactly one point under WATCH, LIKELY_MISS or IMMINENT; NONE only when points = 2

validateEscalation(result, opinion):
  // opinion = { suggestedLevel, citedSignals[], rationaleCode }   (JSON-schema validated; unknown fields are rejected)
  if not borderline(result.points):                       return IGNORED("not borderline")
  if opinion.suggestedLevel <= result.level:              return IGNORED("lower or equal: escalate-only")   // logged as ai_disagrees_lower
  if opinion.suggestedLevel != nextLevel(result.level):   return IGNORED("more than one step")
  fired = set(result.signals.code)
  if opinion.citedSignals is empty or not subset of fired: return REJECTED("cites a signal that did not fire")
  support = sum(points of cited signals)
  if size(cited) < 2 and support < 3:                     return REJECTED("one weak signal is not enough")
  if the member opted out of proactive outreach:          return IGNORED("no outreach allowed")
  return ACCEPTED(newLevel = opinion.suggestedLevel,
                  signal = { code: AI_ESCALATION, points: threshold(newLevel) - result.points, source: AI_SECOND_OPINION })
```

Guarantees, each tested in CI evals: the level never goes down; it never goes up by more than one step; the escalation is stored as its own signal with the run id; and the same score with a hostile member note produces the same decision, because the note never reaches this prompt (the extractor returns only a category). The model's input is the structured signals and a closed-set `reasonCategory`, nothing else.

## 5. Option generation

Target instalment `seq*` is the oldest instalment with money still owed (the first one by due date and sequence). Candidates are built by calling F4's `previewReschedule` and keeping the ones that do not throw a `ScheduleViolation`.

```text
candidates(loan, asOf, recentMonthly, I):
  A  = for d in 14 down to 1: first d where MOVE_DATE(seq*, d) previews OK        // the most room policy and F4 allow
  B  = DEFER_TO_END(seq*) if it previews OK
  Ck = for k in 1..6: REAMORTIZE(extraMonths = k) where it previews OK            // F4 refuses when count <= unpaid instalments
  D  = PAYMENT of floor(recentMonthly * dBurdenBps / 10000 / 100) * 100 cents,
       shown only if it is at least $1 and less than I   (unknown income: half of I, rounded down to whole dollars)
```

For each candidate the engine computes:

```text
owedBefore = [(dueDate, remainingCents)] of current instalments with remaining > 0
owedAfter  = [(dueDate, remainingCents)] of proposed instalments with remaining > 0      // money already paid is re-folded by F4
assert sum(owedBefore.amount) == sum(owedAfter.amount) == outstanding                    // F4 INV: the primitives keep the total

t(date)          = DAYS(asOf, date)
meanDelayDays    = ( sum(a * t) over owedAfter - sum(a * t) over owedBefore ) / outstanding        // rounded half up to 2 decimals
maxInstalment    = max(remaining) over owedAfter
burdenBps        = maxInstalment * 10000 / recentMonthly
sustainable      = burdenBps <= maxBurdenBps (2500)           // null when there is no income data
instalmentsMoved = number of owed rows whose date or amount differs
```

`meanDelayDays` is the shift in the balance-weighted average payment date. It is a fair single number for "how much later does the member end up paying": moving a $150 instalment 14 days later and moving a $15 instalment 140 days later cost the same. It has units of days and can be compared across A, B and C even though C changes amounts.

For C the **offered** variant is the smallest `k` that is sustainable. If none is, it is the largest valid `k`, flagged `sustainable = false` and `stillTight`. The member sees one variant per option.

## 6. OptionRanker

The ranker only chooses which option to label "suggested for your situation". Every option is shown, none is preselected, and the member's choice is final.

```text
rank(options, persistence, hasIncomeData):
  ok = options with policyOk                        // from section 7
  if not hasIncomeData:           return { recommended: null }       // no data, no suggestion
  if persistence == PERSISTENT:
       pool = [o in ok where o.kind == C and o.sustainable]
       if pool is empty: pool = [C with the largest valid k]; flag stillTight
  else:
       pool = ok in order of intrusiveness: A, then B, then C         // the drop looks temporary: least change first
       pool = [first valid in that order]
  recommended = argmin over pool of (meanDelayDays, instalmentsMoved, tier AUTO_POLICY before MAKER_CHECKER, label)
  D is never recommended unless its amount covers the whole instalment.
```

Why two branches: when income stays low for most of the last four weeks, a one-month pause only moves the problem, so the plan must become smaller. When the drop is short, the smallest change that gets over the hump is best and the quickest to approve (A needs no officer).

The rule is a v1 heuristic. The backtest in section 14 measures it, and the policy version changes if it does not hold.

## 7. Policy checks and approval tier

```text
check(kind, history, loan, p):
  require loan.status == ACTIVE and loan.bucket != DPD_60_PLUS and targetInstalment.remaining > 0     else NOT_ELIGIBLE
  A: history.appliedA in the last 183 days < 2                                                         else CAP("move date", 2 per 6 months)
  B: history.appliedB in the last 183 days < 1 and history.appliedB over the loan < 2                  else CAP("pause", 1 per 6 months, 2 per loan)
  C: history.appliedC over the loan < 1                                                                else CAP("restructure", 1 per loan)
  D: always allowed
  return OK or the violated cap with its count (becomes error hardship-policy-violation)

tierFor(kind) = A -> AUTO_POLICY ; B -> MAKER_CHECKER ; C -> MAKER_CHECKER ; D -> NONE
```

Only cases that reached `APPLIED` count toward the caps. The F4 primitives already enforce the date and amount limits (`MOVE_OUT_OF_RANGE`, `MOVE_CROSSES_NEXT`, the $25 floor); this section adds the frequency limits. Complexity: O(1) with two counts from `hardship.case`.

## 8. Stale-preview detection

One **snapshot hash** identifies exactly what the member saw, what the officers approved and what is applied (invariant HINV-14).

```text
snapshotHash = SHA-256( canonicalJson({
   loanId, baseVersion, primitive, args (keys sorted),
   proposed: [ {seq, dueDate, amountCents, remainingCents} ... ]       // the proposed schedule from previewReschedule
}) )
```

- It is stored on `offer_option.snapshot_hash` and copied unchanged to `choice`, to `governance.approval_request` and to the saga.
- It is **recomputed from a fresh preview** at three points: when the member chooses, when each officer decides, and when the saga applies.
- A mismatch means the schedule version changed, or a payment changed what is owed, so what was shown is no longer what would happen. The offer becomes `STALE`, a new revision is built, approvals are voided, and the member must confirm again (`409 hardship-offer-stale`).
- It is not stale just because the date moved: the hash covers the proposed rows, not `asOf`. If tomorrow's preview gives the same rows, the hash is the same.
- Step three must be atomic. F4's `applyReschedule(request, approvalRef)` is extended with the approved proposed rows. Under the loan lock, lending recomputes the proposal and refuses with `RESCHEDULE_PREVIEW_CHANGED` if the rows differ. The same change makes apply idempotent on `approvalRef`; F4's `RescheduleService.apply` does not do that today. Without that, a repayment landing between the saga's preview and the apply could change the result.

Example: Ana's option B hash is taken over `baseVersion = 1`, `DEFER_TO_END {seq: 3}`, and the eight proposed rows in section 13. If Ana pays $50 on Mar 12 before approval, the preview rows change (instalment 3 now has $100 remaining), the hash differs, and Ana is asked to confirm the new numbers.

## 9. Grace hold

A hold stops one fact from being written while a human decides. It never changes how late the loan looks.

Definitions from F4: `graceDays = 5`, and the `GRACE_EXPIRY` timer fires on `missDate = due + 6`, the first COB that writes `InstalmentMissed` for an unpaid instalment.

```text
holdFor(case, instalment, existingHolds, asOf, p):
  place a hold only if ALL of these hold:
     case.status in {OPTION_CHOSEN, PENDING_APPROVAL} and the chosen option needs approval (tier MAKER_CHECKER)
     instalment.seq == case.targetSeq and instalment.remaining > 0 in the current schedule version
     no unreleased hold exists for (loan, seq)
     asOf < missDate and missDate - asOf <= holdWindowDays (3)           // the grace period ends within 3 days
  until = missDate + holdDays - 1                                          // covers COB dates missDate, +1 and +2 (due + 8)

release when: the case becomes APPLIED, REJECTED_BY_OFFICER, DECLINED_BY_MEMBER, CANCELLED_PAID, EXPIRED or SUPERSEDED, or the hold passes `until`

extension: if SLA_BREACH fired and the case is still PENDING_APPROVAL and no extension exists:
           append a second hold row, cause SLA_ESCALATION, until' = until + 2, with an alert. Never silent, at most once.
```

A `HOLD_CHECK` timer at `missDate - 3` (due + 3) re-runs the placement rule for a choice that was made earlier and is still undecided. A choice made inside the window places the hold at once.

**COB interaction.** When F4 fires `GRACE_EXPIRY(loan, seq)` on date `D`:

```text
if an unreleased hold covers (loan, seq) and D <= hold.until:
     skip the InstalmentMissed write and do NOT mark the timer fired      // it stays due, so the next COB considers it again
else:
     F4's normal path                                                     // write InstalmentMissed once, mark the timer fired
```

- Days past due and the bucket are still computed from the dates, so `LateBucketChanged` events and the desk columns stay truthful. The hold suppresses **only** the `InstalmentMissed` fact.
- If the case ends `APPLIED` while the hold is active, the schedule has a new version in which the paused amount is no longer owed on that date, so the miss is never written. If the case ends any other way, the first COB after the hold ends writes the miss with `missedOn` equal to that COB date.
- The miss carries an `attribution`. It is `SLA_BREACH` when the hold ended because our decision SLA was missed (F6 treats that as not the member's miss) and `MEMBER` otherwise. This needs one nullable column on `lending.instalment_missed`.

Worked dates for Ana's instalment due 2027-03-20: `missDate` = 2027-03-26; a hold may be placed from 2027-03-23; `until` = 2027-03-28; the first COB that may write the miss is 2027-03-29.

## 10. SLA timers

```text
businessHours: Monday to Friday, 08:00 to 18:00 America/Los_Angeles (10 hours a day); no holiday calendar in v1

slaDue(start, priority):
  budget = priority ? 4 hours : 10 hours
  t = start
  while budget > 0:
      t = snapToBusinessHours(t)                       // roll forward to the next 08:00 weekday if outside the window
      room = min(budget, endOfBusinessDay(t) - t)
      t = t + room;  budget = budget - room
  return t
```

| Timer | When | Action |
| --- | --- | --- |
| `SLA_50` | 50% of the budget after the request opens | Notify the desk pool |
| `SLA_80` | 80% | Page the admin |
| `SLA_BREACH` | 100% | Sev-2; one audited hold extension; the case stays in the queue |
| `OFFER_REMINDER` | due − 1 day, if the member has not answered | Engagement sends the single reminder |
| `OFFER_EXPIRY` | due + 5 (the last day of grace) | Case `EXPIRED` if no choice was made |
| `HOLD_CHECK` | due + 3 | Place the hold if a choice is waiting for approval |
| `HOLD_EXPIRY` | hold `until` + 1 | Release the hold |

`priority` is true when the target instalment is due within 2 days of the request opening. `PENDING_APPROVAL` never expires on its own: a human must decide. Ana chooses on Thursday 2027-03-11 at 09:42 PT. The request opens then. With a 10-hour budget she has 8 h 18 min left on Thursday and 1 h 42 min on Friday, so `slaDue` is Friday 2027-03-12 09:42 PT. `SLA_50` is Thursday 14:42, `SLA_80` is Thursday 17:42.

## 11. Grounding checker

Goal: nothing the AI writes can contain a number, date or option that the code did not produce. The member message has a fixed template header and footer (opt-out, decline, contact); the AI writes only the middle paragraph. The checker runs on that paragraph and on the officer brief.

```text
ground(narrative, allowed, locale):
  tokens = extractNumbers(narrative, locale)      // money, percentages, counts, dates, durations, option references
  for each token:
      value = normalise(token)
      if value in ignorable (see below): continue
      if not matches(value, allowed): return FAIL(token, kind)
  forbidden = find(narrative, [URL, email, phone number, a currency other than USD, a claim word])
  if forbidden is not empty: return FAIL(forbidden)
  if locale and language(narrative) differ: return FAIL("language")
  if length(narrative) > limit (480 characters member, 1200 officer): return FAIL("length")
  return PASS
```

**Extraction and normalisation.** The extractor reads digits and number words (English and Spanish, zero to twenty).

| Kind | Examples read | Normal form |
| --- | --- | --- |
| Money | `$150`, `$1,200`, `150 dollars`, `$150.00`, `150 dólares` | cents (15000) |
| Percent | `52%`, `52 percent`, `52 por ciento` | basis points (5200) |
| Date | `March 20`, `Mar 20`, `20 de marzo`, `2027-03-20`, `April 3rd` | ISO date; the year is the nearest one to the scan date within plus or minus one year |
| Duration | `14 days`, `one month`, `2 more months` | days or months as an integer |
| Count | `8 payments`, `eight instalments`, `4 weeks` | integer |
| Option | `Option A`, `pause`, `pausa`, `smaller payments` | label A to D, by a fixed phrase table |

**Allowed set.** `allowed` is built by code from the case: for the member audience, the target instalment amount and date, each option's summary numbers, the drop percentage in whole percent, the lookback of four weeks; for the officer audience, also every signal's evidence, the points, the thresholds and each option's metrics. The model does no arithmetic. Anything it might want to say that needs a calculation must already be a field in the tool output.

**Tolerance.**

| Kind | Rule |
| --- | --- |
| Money, dates, counts, durations, option labels | Exact after normalisation. "About $110" for $112 fails |
| Percent | Allowed when within 50 bps of a permitted value; the permitted values include the exact figure, the figure rounded to a whole percent and rounded to one decimal |
| Ignorable | Option letters in "Option A", years that are part of a date, list ordinals ("1.", "2.") and the digits of the fixed template text |

**Claims.** Words that state an outcome the system has not reached are forbidden unless a template phrase supplies them: "approved", "guaranteed", "waived", "forgiven", "no effect on your record". An AI paragraph may say what the options are, never that one has been granted.

**Mismatch handling.** Any FAIL replaces the paragraph with the template, records `grounding = FAILED_FELL_BACK` with the first failing token (a hash, not the text), increments `seed_agent_grounding_failures_total` and, at more than 3 in an hour, trips the kill switch.

Example. Allowed money for Ana: 15000, 11200, 11300, 6900, 8100. The text "Option C makes each payment $112 or $113" passes. "Option C makes each payment about $110" fails on 11000. "Your payment is waived" fails on the claim word.

Complexity: O(L) in the narrative length with a handful of regular expressions and a hash-set lookup.

## 12. Rubber-stamp detector

Meaningful human review is the control that makes the whole feature acceptable, so the system measures whether it is happening.

```text
detect(decisions):                       // the officer's last 20 decisions, with decidedMs from panel open and evidenceViewedMs
  if size < 20: return NONE
  fastShare     = share where decidedMs < 10000 and evidenceViewedMs < 10000
  approvalShare = share where decision == APPROVE
  if fastShare >= 0.5 and approvalShare >= 0.9: return FLAG
  return NONE
```

On a flag: notify the admin; require a note of at least 20 characters on that officer's next 10 decisions; raise blind second-review sampling for that officer from 5% to 25% for the next 40 decisions. Nothing is blocked automatically. A second metric is tracked for the dashboard: the approval rate when the officer's choice matches the suggested option versus when it does not, which shows automation bias. Complexity: O(20) per decision.

## 13. Worked example: Ana

**Loan.** $1,200, 8 instalments, due day 20, disbursed 2027-01-02, first due 2027-01-20 (at least 14 days after disbursement). `RepaymentPolicy` v1, remainder 0, so every instalment is $150.

| seq | Due date | Amount | State on 2027-03-10 |
| --- | --- | --- | --- |
| 1 | 2027-01-20 | $150 | paid |
| 2 | 2027-02-20 | $150 | paid |
| 3 | 2027-03-20 | $150 | owed (target, due in 10 days) |
| 4 | 2027-04-20 | $150 | owed |
| 5 | 2027-05-20 | $150 | owed |
| 6 | 2027-06-20 | $150 | owed |
| 7 | 2027-07-20 | $150 | owed |
| 8 | 2027-08-20 | $150 | owed |

Scan date `asOf` = 2027-03-10. Outstanding $900. Days past due 0, bucket `CURRENT`. The instalment is due in exactly 10 days, so it is inside the scan window (`scanLeadDays = 10`).

**Weekly inflow (13 buckets of 7 days, ending 2027-03-09).**

| w1 | w2 | w3 | w4 | w5 | w6 | w7 | w8 | w9 | w10 | w11 | w12 | w13 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| $240 | $215 | $260 | $230 | $245 | $225 | $235 | $250 | $220 | $115 | $100 | $112 | $105 |

**Features.**

| Feature | Value |
| --- | --- |
| Median of 13 weeks (sorted: 100, 105, 112, 115, 215, 220, 225, 230, 235, 240, 245, 250, 260; the 7th) | $225, so `baselineWeekly` = 22,500 cents |
| `baseline4` = 4 × 22,500 | 90,000 cents |
| `last4` = 115 + 100 + 112 + 105 | 43,200 cents |
| `dropBps` = (90,000 − 43,200) × 10,000 / 90,000 | 5,200 (52.00%) |
| `recentMonthly` = 43,200 × 30 / 28 | 46,285 cents ($462.85) |
| `coverageBps` = 46,285 × 3,000 / 15,000 | 9,257 (0.9257) |
| Weeks below 70% of baseline (157.5): w10 to w13 | 4 of 4, so `PERSISTENT` |

**Score.**

| Signal | Evidence | Points |
| --- | --- | --- |
| `INCOME_DROP_SEVERE` | `dropBps` 5,200 ≥ 5,000 | 5 |
| `LOW_COVERAGE` | `coverageBps` 9,257 < 10,000 and ≥ 6,000 | 2 |
| S3, S4, S5, S6 | no failed autopay, no payday slip, no lateness, no savings stress | 0 |
| **Total** | | **7** → `LIKELY_MISS` (6 to 8) |

7 is not borderline (2, 5 or 8), so the AI second opinion does not run. A case opens as `DETECTED` and the daily scan creates the offer.

**Option previews with F4's real rules** (`asOf` = 2027-03-10, outstanding $900, six owed instalments):

*Option A: `MOVE_DATE(seq 3, days 14)`.* The new date is 2027-04-03, which is after today and before seq 4 (2027-04-20), so F4 accepts it. The rows are sorted by date and renumbered.

| New seq | Due date | Amount |
| --- | --- | --- |
| 1, 2 | 2027-01-20, 2027-02-20 | $150 each (paid) |
| 3 | 2027-04-03 | $150 |
| 4 | 2027-04-20 | $150 |
| 5 | 2027-05-20 | $150 |
| 6 | 2027-06-20 | $150 |
| 7 | 2027-07-20 | $150 |
| 8 | 2027-08-20 | $150 |

*Option B: `DEFER_TO_END(seq 3)`.* The last instalment still owed is 2027-08-20, so the month after it is September. The month after today is March, so the later one, September, wins: the deferred $150 lands on 2027-09-20. The March row disappears.

| New seq | Due date | Amount |
| --- | --- | --- |
| 1, 2 | 2027-01-20, 2027-02-20 | $150 each (paid) |
| 3 | 2027-04-20 | $150 |
| 4 | 2027-05-20 | $150 |
| 5 | 2027-06-20 | $150 |
| 6 | 2027-07-20 | $150 |
| 7 | 2027-08-20 | $150 |
| 8 | 2027-09-20 | $150 |

*Option C: `REAMORTIZE(extraMonths k)`.* Outstanding 90,000 cents, 6 unpaid instalments, floor $25, so the count is `min(6 + k, floor(90,000 / 2,500) = 36)`. The first month is the first due day after today and after any kept instalment: 2027-03-20 (instalments 1 and 2 are before today). F4's split puts the remainder at the back.

| k | Count | Amounts | Last due |
| --- | --- | --- | --- |
| 1 | 7 | 128, 128, 128, 129, 129, 129, 129 | 2027-09-20 |
| **2** | **8** | **112, 112, 112, 112, 113, 113, 113, 113** | **2027-10-20** |
| 3 | 9 | 100 × 9 | 2027-11-20 |
| 6 | 12 | 75 × 12 | 2028-02-20 |

For k = 2 the dates are 2027-03-20, 04-20, 05-20, 06-20, 07-20, 08-20, 09-20 and 10-20. Together with the two paid instalments it is a 10-instalment loan.

*Option D: payment.* `floor(46,285 × 1,500 / 10,000 / 100) × 100` = 6,900 cents ($69). F4's payment preview applies it to instalment 3, leaving $81 due on 2027-03-20: `LEAVES_SHORTFALL`.

**Metrics.** Days are counted from 2027-03-20 (day 0). Old balance-weighted sum for the six owed instalments (days 0, 31, 61, 92, 122, 153; sum 459): 150 × 459 = 68,850 dollar-days.

| Option | New weighted sum (dollar-days) | `meanDelayDays` = difference / 900 | `maxInstalment` | `burdenBps` | Sustainable (≤ 2,500) |
| --- | --- | --- | --- | --- | --- |
| A (14 days) | 150 × (14 + 31 + 61 + 92 + 122 + 153 = 473) = 70,950 | 2,100 / 900 = **2.33** | $150 | 3,240 | no |
| B | 150 × (31 + 61 + 92 + 122 + 153 + 184 = 643) = 96,450 | 27,600 / 900 = **30.67** | $150 | 3,240 | no |
| C, k = 1 | 128 × (0 + 31 + 61) + 129 × (92 + 122 + 153 + 184) = 11,776 + 71,079 = 82,855 | 14,005 / 900 = 15.56 | $129 | 2,787 | no |
| **C, k = 2** | 112 × (0 + 31 + 61 + 92) + 113 × (122 + 153 + 184 + 214) = 20,608 + 76,049 = 96,657 | 27,807 / 900 = **30.90** | $113 | **2,441** | **yes** |
| C, k = 6 | 75 × 2,020 = 151,500 | 82,650 / 900 = 91.83 | $75 | 1,620 | yes |

(`burdenBps` = maxInstalment × 10,000 / 46,285; for example 11,300 × 10,000 / 46,285 = 2,441.)

**Ranker.** The drop is `PERSISTENT`, so only sustainable C variants are in the pool. The smallest sustainable `k` is 2, so **the suggested option is C (k = 2)**. B is shown as an alternative with the warning `NOT_SUSTAINABLE_AT_CURRENT_INCOME`: at $462.85 a month, a $150 instalment takes 32.4% of recent income, and April could look the same.

**Policy and tier.** Ana has no earlier cases, so every cap passes. A is `AUTO_POLICY`, B and C are `MAKER_CHECKER`, D is `NONE`.

**What Ana does.** She reads the SMS on Thursday 2027-03-11 and chooses B ("June is my busy month"). The case moves to `OPTION_CHOSEN` then `PENDING_APPROVAL`. `slaDue` is Friday 2027-03-12 09:42 PT (section 10). The grace period ends at the earliest on 2027-03-26, so no hold is placed (the miss date is not within 3 days of 2027-03-12). The officer sees the brief and the warning; the officer approves with reason `INCOME_DROP_VERIFIED`; the checker countersigns.

**Execution.** The saga recomputes the preview; the snapshot hash matches. `applyReschedule(DEFER_TO_END, seq 3, approvalRef)` creates schedule version 2 with reason `HARDSHIP_PAUSE`, the table under Option B. The oldest unpaid instalment is now 2027-04-20, so days past due is 0 and the bucket is `CURRENT`. F4's chain revises the PayPal subscription; credit-record marks the case "on plan"; Ana gets the confirmation SMS.

**If nothing is done.** On 2027-03-20 the autopay charge fails: `AUTOPAY_FAILED` adds 3 (total 10, `IMMINENT`). On 2027-03-21 she is in grace: `IN_GRACE` adds 3 (total 13). The `InstalmentMissed` fact would be written on 2027-03-26 (due + 6).

## 14. Calibration, backtest and fairness check

The scorecard, option rule and thresholds are hypotheses until measured. All data below is **synthetic**. The numbers are targets to test, not claims about real members.

**Synthetic cohort** (the Postman Simulator or a seeded generator; 5,000 loans over 12 months):

| Archetype | Weekly inflow model |
| --- | --- |
| `STEADY` | Log-normal around a member mean, coefficient of variation 0.15 |
| `GIG` | Same, coefficient of variation 0.35 |
| `SEASONAL` | Steady with a 6-week dip each year |
| `STEP_DOWN` | A permanent drop of 30% to 70% starting at a random week |
| `TEMP_DIP` | A drop of 30% to 70% for 3 to 6 weeks, then recovery |

**Ground truth.** Each member has a hidden `spendShare` drawn from 0.20 to 0.45, and a 5% chance per cycle of an unexpected expense (a random $20 to $120). `wouldMiss` is true when `spendShare × inflow in the 30 days before due − expense < instalment`. The hidden share and the expense are not features of the scorecard, which lowers the risk that the label is just the scorecard restated.

**Metrics, computed at the level LIKELY_MISS or above, at the first scan inside the window:**

| Metric | Definition | Target to test |
| --- | --- | --- |
| Precision at alert | true misses among alerted loans | at least 0.50 |
| Recall at 5 days | true misses alerted 5 or more days before the due date, over all true misses | at least 0.60 |
| Alerts per 100 | cases opened per 100 loan-instalment cycles | at most 12 |
| False-pause cost | for alerts on members who would have paid: the acceptance probability (0.5) × the `meanDelayDays` of the suggested option, averaged per false alert | reported, with the distribution |
| Time to relief | days from alert to an applied option, with a 1-business-day SLA | median at most 2 days |

**Procedure.** Split by seed: tune the point values and thresholds on seeds 1 to 3, evaluate once on seeds 4 to 5, freeze the result as `scorecard_version`. Never tune on the demo cohort. Report sensitivity: re-run with `usableShareBps` set to 2,000 and 4,500 and with the hidden `spendShare` range shifted by plus or minus 0.1, so the result does not depend on one lucky parameter. A challenger model (logistic regression or gradient boosting on the same features) may be trained for comparison, in shadow only, after the hackathon.

**Fairness parity check.** The simulator tags each member with neutral attributes that the scorecard never reads: `incomeType`, `loanSizeBand`, `tenureBand`, `languagePref`. They exist only to audit the result.

```text
for each tag with groups g:
   alertRate[g], TPR[g] = recall, FPR[g] = share of would-pay members who were alerted, needRate[g] = true miss rate
   alertToNeed[g] = alertRate[g] / needRate[g]
pass when, for every pair of groups:
   |TPR gap| <= 0.10   and   |FPR gap| <= 0.05   and   alertToNeed ratio within [0.80, 1.25]
```

A failure does not change anything by itself. It opens a review, and the report is attached to the `policy_version`. Language also gets a delivery check: median time from case open to message sent must not differ by more than a quarter between `en` and `es`. In production there is no demographic data to test with, which is why the design avoids proxies instead of trying to correct for them afterwards.

## 15. Complexity summary

| Function | Time | Budget |
| --- | --- | --- |
| `extractFeatures` | O(13) | well under 0.1 ms |
| `score`, `applyHysteresis`, `tierFor`, `check` | O(1) | well under 0.1 ms |
| Option generation (up to 14 + 1 + 6 previews through F4) | O(21 × (n + m) · n) | under 20 ms at n = 36, m = 100 (F4 NFR-04 is 5 ms per call) |
| `metrics`, `rank` | O(n) per option | under 1 ms |
| `snapshotHash` | O(n) | under 0.1 ms |
| `holdFor`, `slaDue` | O(1) | under 0.1 ms |
| `ground` | O(L) in narrative length | under 1 ms |
| `detect` | O(20) | under 0.1 ms |
| Daily scan per loan (two reads dominate) | about 6 ms | 35,000 loans in about 30 seconds per shard group of 4,400 |
