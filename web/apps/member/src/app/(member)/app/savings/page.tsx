import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { Plus, Route as RouteIcon, Sprout } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { GoalCard } from "@/features/savings/components/goal-card";
import { isOpen } from "@/features/savings/model";
import { getSavingsOverview } from "@/features/savings/server/queries";

export const metadata: Metadata = { title: "Savings" };

/** The backend allows three open goals at once (waiting ones count). */
const MAX_OPEN_GOALS = 3;

export default async function SavingsPage() {
  const { goals, streakMonths, totalSavedCents, nextDeposits } = await getSavingsOverview();
  const current = goals.filter((goal) => goal.status !== "EXPIRED" && goal.status !== "CANCELLED");
  const earlier = goals.filter((goal) => goal.status === "EXPIRED" || goal.status === "CANCELLED");
  const atLimit = goals.filter((goal) => isOpen(goal.status)).length >= MAX_OPEN_GOALS;
  const newGoal = "/app/savings/new" as Route;

  return (
    <div className="flex flex-col gap-10">
      <PageHeader
        eyebrow="Your savings"
        title="Savings goals"
        description="Put a little aside each month. It stays yours to take out, and steady saving can open the door to a 0% loan."
        actions={
          <>
            <Button asChild variant="outline">
              <Link href={"/app/journey" as Route}>
                <RouteIcon aria-hidden="true" />
                Your journey
              </Link>
            </Button>
            {atLimit ? null : (
              <Button asChild>
                <Link href={newGoal}>
                  <Plus aria-hidden="true" />
                  New goal
                </Link>
              </Button>
            )}
          </>
        }
      />

      {goals.length === 0 ? (
        <EmptyState
          icon={<Sprout aria-hidden="true" />}
          title="Start your first goal"
          description="Save from $5 a month. It is yours to take out whenever you need it, and it never costs a fee."
          action={
            <Button asChild size="lg">
              <Link href={newGoal}>Start a goal</Link>
            </Button>
          }
        />
      ) : (
        <>
          {totalSavedCents !== null || streakMonths !== null ? (
            <section aria-label="Your savings at a glance" className="grid gap-4 sm:grid-cols-2">
              {totalSavedCents !== null ? <Kpi label="Saved so far" value={formatMoney(totalSavedCents)} hint="Across all your goals" /> : null}
              {streakMonths !== null ? (
                <Kpi
                  label="Saving streak"
                  value={streakMonths}
                  hint={streakMonths === 1 ? "month in a row" : "months in a row"}
                />
              ) : null}
            </section>
          ) : null}

          {atLimit ? (
            <p className="text-sm text-body">You have three goals open, the most at one time. Close one to start another.</p>
          ) : null}

          {current.length > 0 ? (
            <section aria-label="Your goals" className="grid gap-4 md:grid-cols-2">
              {current.map((goal) => (
                <GoalCard key={goal.id} goal={goal} streakMonths={streakMonths} nextDepositDate={nextDeposits[goal.id] ?? null} />
              ))}
            </section>
          ) : null}

          {earlier.length > 0 ? (
            <section aria-labelledby="earlier-goals" className="flex flex-col gap-4">
              <h2 id="earlier-goals" className="text-display-sm text-ink">
                Earlier goals
              </h2>
              <div className="grid gap-4 md:grid-cols-2">
                {earlier.map((goal) => (
                  <GoalCard key={goal.id} goal={goal} streakMonths={null} />
                ))}
              </div>
            </section>
          ) : null}
        </>
      )}
    </div>
  );
}
