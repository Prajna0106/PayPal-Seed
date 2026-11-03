import { formatMoney } from "@paypal-seed/ui/lib/format";
import { Flame, PiggyBank } from "lucide-react";
import { plural } from "../copy";
import type { SavingsSection } from "../model";
import { DrawBar } from "./home-motion";
import { SectionCard } from "./section-card";

/** Total saved, the streak, and the goal closest to done. Every figure is copied from the backend. */
export function SavingsCard({ savings }: { savings: SavingsSection }) {
  const goals = savings.goals;
  // The bar draws the goal nearest to its target that is not yet reached; the numbers beside it are the real ones.
  const focus = goals
    ?.filter((goal) => goal.target.cents > 0 && goal.saved.cents < goal.target.cents)
    .sort((a, b) => b.saved.cents / b.target.cents - a.saved.cents / a.target.cents)[0];

  return (
    <SectionCard title="Savings" icon={<PiggyBank aria-hidden="true" />} href="/app/savings" linkLabel="See your savings">
      <div className="flex flex-col gap-1">
        <p className="font-display text-4xl font-light tracking-tight text-ink tabular-nums">{formatMoney(savings.totalSaved.cents)}</p>
        <p className="text-sm text-muted-ink">
          saved with Seed
          {goals ? ` across ${plural(goals.length, "goal", "goals")}` : ""}
        </p>
      </div>

      <p className="flex items-center gap-2 text-sm text-body">
        <Flame aria-hidden="true" className="size-4 text-muted-ink" />
        {savings.currentStreakMonths > 0
          ? `${plural(savings.currentStreakMonths, "month", "months")} in a row`
          : "Your streak starts with your next deposit"}
      </p>

      {focus ? (
        <div className="mt-auto flex flex-col gap-2">
          <DrawBar
            percent={(focus.saved.cents / focus.target.cents) * 100}
            label={`${focus.name}: ${formatMoney(focus.saved.cents)} of ${formatMoney(focus.target.cents)}`}
          />
          <p className="flex justify-between gap-3 text-xs text-muted-ink">
            <span className="truncate">{focus.name}</span>
            <span className="shrink-0 tabular-nums">
              {formatMoney(focus.saved.cents)} of {formatMoney(focus.target.cents)}
            </span>
          </p>
        </div>
      ) : null}
    </SectionCard>
  );
}
