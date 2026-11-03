import { Money } from "@paypal-seed/ui/brand/money";
import { Button } from "@paypal-seed/ui/components/button";
import { Progress } from "@paypal-seed/ui/components/progress";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { CircleCheck, ExternalLink } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import type { ProductGoalModel } from "../server/queries";

const STATUS: Record<ProductGoalModel["status"], string> = {
  PENDING_APPROVAL: "Waiting for you to agree at PayPal",
  ACTIVE: "Saving",
  PAUSED: "Paused",
  COMPLETED: "Saved",
  CANCELLED: "Stopped",
  EXPIRED: "Not started",
};

export function ProductGoalCard({ goal }: { goal: ProductGoalModel }) {
  // Display only: the bar shows the saved share of the target, capped at full.
  const percent = goal.targetCents > 0 ? Math.min(100, Math.round((goal.savedCents / goal.targetCents) * 100)) : 0;
  const headingId = `goal-${goal.goalId}`;
  return (
    <li className="flex flex-col gap-4 rounded-2xl border border-hairline bg-surface p-5">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex flex-col gap-1">
          <h2 id={headingId} className="text-display-sm text-ink">
            {goal.title}
          </h2>
          <p className="text-sm text-body">
            {goal.isDemo ? "Demo product" : goal.merchant} · {STATUS[goal.status]}
            {goal.status === "ACTIVE" ? ` · ${formatMoney(goal.monthlyDollars * 100)} a month` : ""}
          </p>
        </div>
        <Button asChild variant="ghost" size="sm">
          <Link href={`/app/savings/${goal.goalId}` as Route}>See this goal</Link>
        </Button>
      </div>

      <div className="flex flex-col gap-2">
        <Progress value={percent} aria-labelledby={headingId} aria-valuetext={`${percent} percent saved`} />
        <p className="text-sm text-body">
          <Money cents={goal.savedCents} className="text-ink" /> saved of <Money cents={goal.targetCents} />
        </p>
      </div>

      {goal.approvalUrl ? (
        <Button asChild variant="outline" className="self-start">
          <a href={goal.approvalUrl} rel="noopener noreferrer">
            Agree at PayPal to start saving
            <ExternalLink aria-hidden="true" />
          </a>
        </Button>
      ) : null}

      {goal.reached ? (
        <div role="status" className="flex flex-col gap-3 rounded-xl bg-success-soft p-4">
          <p className="flex items-center gap-2 text-base font-medium text-ink">
            <CircleCheck className="size-5 text-success" aria-hidden="true" />
            You have saved enough for this
          </p>
          {goal.priceNowCents !== null ? (
            <p className="text-sm text-body">
              The shop now lists it at <Money cents={goal.priceNowCents} />. Your goal stays at <Money cents={goal.targetCents} />.
            </p>
          ) : null}
          {goal.buyUrl ? (
            <>
              <p className="text-sm text-body">
                Whenever you are ready, you can go to {goal.merchant} to buy it. Many shops let you pay with PayPal at checkout. Your money stays in your savings until you take it out, so withdraw what you need from your goal first. There is no rush, and no pressure to buy.
              </p>
              <Button asChild className="self-start">
                <a href={goal.buyUrl} target="_blank" rel="noopener noreferrer">
                  Buy at {goal.merchant}
                  <ExternalLink aria-hidden="true" />
                  <span className="sr-only"> (opens in a new tab)</span>
                </a>
              </Button>
            </>
          ) : (
            <p className="text-sm text-body">
              {goal.isDemo
                ? "This was a demo product, so there is no real shop to send you to. Your savings are untouched."
                : "We cannot find the shop link for this one right now. Your savings are untouched."}
            </p>
          )}
        </div>
      ) : null}
    </li>
  );
}
