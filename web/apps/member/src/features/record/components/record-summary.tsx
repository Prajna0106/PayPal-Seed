import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { SeedMark } from "@paypal-seed/ui/brand/seed-mark";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Progress } from "@paypal-seed/ui/components/progress";
import { formatBusinessDate, formatMoney, formatPercentBps } from "@paypal-seed/ui/lib/format";
import { CheckCircle2, ShieldCheck } from "lucide-react";
import { LEVEL_BLURB, LEVEL_NAME, plural, type RecordView } from "../model";

/** The level, what it unlocks and the plain numbers behind it. There is no score anywhere on this page. */
export function RecordSummary({ record }: { record: RecordView }) {
  return (
    <div className="flex flex-col gap-6">
      <section aria-label="Your level" className="flex flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6 shadow-soft sm:flex-row sm:items-center sm:gap-6">
        <SeedMark className="size-16 shrink-0" />
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center gap-3">
            <StatusPill tone="ink">Your level</StatusPill>
            {record.levelSince ? (
              <span className="text-sm text-muted-ink">Since {formatBusinessDate(record.levelSince, "long")}</span>
            ) : null}
          </div>
          <h2 className="text-display-md text-ink">{LEVEL_NAME[record.level]}</h2>
          <p className="max-w-prose text-body">{LEVEL_BLURB[record.level]}</p>
          {record.since ? (
            <p className="text-sm text-muted-ink">Your record began {formatBusinessDate(record.since, "month")}.</p>
          ) : null}
        </div>
      </section>

      <section aria-label="Your record in numbers" className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Kpi
          label="Loans repaid on plan"
          value={record.loans.repaidOnPlan}
          hint={record.loans.repaidOnPlan === 0 ? "Your first one counts a lot" : "Including any hardship plan"}
        />
        <Kpi
          label="Paying on time"
          value={record.onTimeBand ?? "Not yet"}
          hint={record.onTimeBand ? "Of saving months and payments" : "Shows up after your first month"}
        />
        <Kpi
          label="Loan limit unlocked"
          value={formatMoney(record.loanLimitCents)}
          hint="The higher of this and what your saving streak unlocks"
        />
        <Kpi label="On-time saving months" value={record.savings.onTimeMonths} hint="Months you added to your savings" />
      </section>
    </div>
  );
}

/** What is left for the next level, in the API's own words and counts. */
export function NextLevel({ record }: { record: RecordView }) {
  const next = record.next;
  if (!next) {
    return (
      <p className="flex items-center gap-2 text-body">
        <CheckCircle2 className="size-5 text-success" aria-hidden="true" />
        You are at the highest level. Thank you.
      </p>
    );
  }
  const savingsDone = record.savings.onTimeMonths;
  const loansDone = record.loans.repaidOnPlan;
  return (
    <div className="flex flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6">
      <h3 className="text-lg font-medium text-ink">Next level: {LEVEL_NAME[next.level]}</h3>
      <div className="grid gap-5 sm:grid-cols-2">
        <Step
          label="On-time saving months"
          done={savingsDone}
          toGo={next.onTimeSavingMonthsToGo}
          hint={
            next.onTimeSavingMonthsToGo > 0
              ? `${plural(next.onTimeSavingMonthsToGo, "more month", "more months")} of saving`
              : "You have saved enough months"
          }
        />
        <Step
          label="Loans repaid on plan"
          done={loansDone}
          toGo={next.loansRepaidOnPlanToGo}
          hint={
            next.loansRepaidOnPlanToGo > 0
              ? `${plural(next.loansRepaidOnPlanToGo, "more loan", "more loans")} to repay on plan`
              : "You have repaid enough loans"
          }
        />
      </div>
      {next.onTimeShareTooLow ? (
        <p className="text-sm text-body">
          Paying on time most months gets you there. This level looks for {formatPercentBps(next.requiredOnTimeBps)} on time.
        </p>
      ) : null}
      {next.latePaymentOpen ? (
        <p className="text-sm text-body">A late payment is still open. Paying it lets you move up, and it never moves you down.</p>
      ) : null}
    </div>
  );
}

function Step({ label, done, toGo, hint }: { label: string; done: number; toGo: number; hint: string }) {
  // The target is what the member has plus what the API says is still to go; this only draws the bar.
  const total = done + toGo;
  const percent = total === 0 ? 100 : Math.round((done / total) * 100);
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="font-medium text-ink">{label}</span>
        <span className="text-muted-ink tabular-nums">
          {done} of {total}
        </span>
      </div>
      <Progress
        value={percent}
        aria-label={label}
        className="h-2.5 bg-surface-strong *:data-[slot=progress-indicator]:bg-ink"
      />
      <p className="text-sm text-body">{hint}</p>
    </div>
  );
}

/** A hardship plan never lowers the record (ADR 0011 and 0014). Said calmly, where a worried member will see it. */
export function HardshipNote({ plans }: { plans: number }) {
  return (
    <aside className="flex gap-3 rounded-2xl border border-hairline bg-canvas-soft p-5">
      <ShieldCheck className="mt-0.5 size-5 shrink-0 text-ink" aria-hidden="true" />
      <div className="flex flex-col gap-1">
        <h3 className="text-sm font-medium text-ink">Asking for help never hurts your record</h3>
        <p className="text-sm text-body">
          A hardship plan counts as on plan. A late payment or a pause in saving does not lower your level either.
          {plans > 0 ? ` You have used ${plural(plans, "plan", "plans")} so far, and each one counts as on plan.` : ""}
        </p>
      </div>
    </aside>
  );
}
