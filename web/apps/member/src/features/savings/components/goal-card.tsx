import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Progress } from "@paypal-seed/ui/components/progress";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowRight } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { GOAL_STATUS, type Goal, progressPercent, WALLET_LABEL } from "../model";
import { ApprovalNote } from "./approval-note";

/** When the next deposit happens, in the words a member needs. The backend sends no date, so none is shown. */
export function nextDepositText(goal: Goal): string {
  switch (goal.status) {
    case "ACTIVE":
      return goal.wallet === "PAYPAL" ? "PayPal takes it each month" : "When you add money";
    case "PAUSED":
      return "Paused for now";
    case "COMPLETED":
      return "You reached your goal";
    case "PENDING_APPROVAL":
      return "After you agree at PayPal";
    default:
      return "None";
  }
}

export function monthsInARow(months: number): string {
  return months === 1 ? "1 month in a row" : `${months} months in a row`;
}

/** One goal on the savings list. The whole card opens the goal; the PayPal link sits above it. */
export function GoalCard({ goal, streakMonths }: { goal: Goal; streakMonths: number | null }) {
  const status = GOAL_STATUS[goal.status];
  const href = `/app/savings/${goal.id}` as Route;
  const pending = goal.status === "PENDING_APPROVAL";
  const closed = goal.status === "EXPIRED" || goal.status === "CANCELLED";
  const showStreak = streakMonths !== null && (goal.status === "ACTIVE" || goal.status === "PAUSED");

  return (
    <article className="group relative flex flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6 transition-shadow hover:shadow-[0_4px_16px_rgba(0,0,0,0.04)]">
      <header className="flex items-start justify-between gap-4">
        <div className="flex min-w-0 flex-col gap-1">
          <p className="text-eyebrow text-muted-ink">
            {WALLET_LABEL[goal.wallet]} · {formatMoney(goal.monthlyCents)} a month
          </p>
          <h2 className="text-display-sm break-words text-ink">
            <Link href={href} className="after:absolute after:inset-0 after:rounded-2xl focus-visible:outline-none">
              {goal.name}
            </Link>
          </h2>
        </div>
        <StatusPill tone={status.tone} className="shrink-0">
          {status.label}
        </StatusPill>
      </header>

      {pending ? <ApprovalNote goal={goal} className="relative z-10" /> : null}

      {closed ? (
        <p className="text-sm text-body">{status.hint}</p>
      ) : (
        <>
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <Fact label="Saved so far" value={formatMoney(goal.savedCents)} />
            <Fact label="Each month" value={formatMoney(goal.monthlyCents)} />
            {showStreak ? <Fact label="Saving streak" value={monthsInARow(streakMonths)} /> : null}
            <Fact label="Next deposit" value={nextDepositText(goal)} />
            <Fact label="Wallet" value={WALLET_LABEL[goal.wallet]} />
            {goal.targetDate ? <Fact label="Target date" value={formatBusinessDate(goal.targetDate, "medium")} /> : null}
          </dl>

          {!pending && goal.targetCents > 0 ? (
            <div className="flex flex-col gap-2">
              <Progress
                value={progressPercent(goal)}
                aria-label={`${formatMoney(goal.savedCents)} saved of ${formatMoney(goal.targetCents)}`}
                className="h-1.5"
              />
              <p className="text-xs text-muted-ink">
                {formatMoney(goal.savedCents)} of {formatMoney(goal.targetCents)}
              </p>
            </div>
          ) : null}
        </>
      )}

      <span className="inline-flex items-center gap-1 text-sm font-medium text-ink" aria-hidden="true">
        Open this goal <ArrowRight className="size-4 transition-transform group-hover:translate-x-0.5" />
      </span>
    </article>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-muted-ink">{label}</dt>
      <dd className="text-base font-medium text-ink">{value}</dd>
    </div>
  );
}
