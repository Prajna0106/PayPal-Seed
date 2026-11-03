import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { Progress } from "@paypal-seed/ui/components/progress";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowLeft, Route as RouteIcon } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { z } from "zod";
import { ApprovalNote } from "@/features/savings/components/approval-note";
import { PauseResume } from "@/features/savings/components/pause-resume";
import { Section } from "@/features/savings/components/section";
import { TopUpSection } from "@/features/savings/components/top-up-section";
import { WithdrawForm } from "@/features/savings/components/withdraw-form";
import { WithdrawalList } from "@/features/savings/components/withdrawal-list";
import { canTopUp, canWithdraw, GOAL_STATUS, progressPercent, WALLET_LABEL } from "@/features/savings/model";
import { getGoalDetail } from "@/features/savings/server/queries";

export const metadata: Metadata = { title: "Your goal" };

export default async function GoalPage({ params, searchParams }: PageProps<"/app/savings/[goalId]">) {
  const [{ goalId }, { welcome }] = await Promise.all([params, searchParams]);
  if (!z.uuid().safeParse(goalId).success) {
    notFound();
  }
  const { goal, withdrawals, streakMonths } = await getGoalDetail(goalId);
  const status = GOAL_STATUS[goal.status];
  const pending = goal.status === "PENDING_APPROVAL";
  const canPause = goal.wallet === "PAYPAL" && (goal.status === "ACTIVE" || goal.status === "PAUSED");

  return (
    <div className="flex flex-col gap-10">
      <Button asChild variant="ghost" size="sm" className="-ml-3 min-h-11 self-start">
        <Link href={"/app/savings" as Route}>
          <ArrowLeft aria-hidden="true" />
          Your goals
        </Link>
      </Button>

      <PageHeader
        eyebrow={`${WALLET_LABEL[goal.wallet]} · ${formatMoney(goal.monthlyCents)} a month`}
        title={goal.name}
        description={status.hint}
        actions={<StatusPill tone={status.tone}>{status.label}</StatusPill>}
      />

      {welcome && !pending ? (
        <p role="status" className="rounded-xl bg-success-soft px-4 py-3 text-sm text-ink">
          Your goal is ready.{goal.wallet === "VENMO" ? " Add money below whenever you like." : ""}
        </p>
      ) : null}

      {pending ? <ApprovalNote goal={goal} /> : null}

      <section aria-label="Where this goal stands" className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-3">
          <Kpi
            label="Saved so far"
            value={formatMoney(goal.savedCents)}
            hint={goal.reservedCents > 0 ? `${formatMoney(goal.reservedCents)} is on its way out` : undefined}
          />
          <Kpi
            label="Target"
            value={formatMoney(goal.targetCents)}
            hint={goal.targetDate ? `By ${formatBusinessDate(goal.targetDate, "medium")}` : undefined}
          />
          <Kpi
            label="Saving streak"
            value={streakMonths ?? "—"}
            hint={streakMonths === null ? "Not available right now" : streakMonths === 1 ? "month in a row" : "months in a row"}
          />
        </div>
        {!pending ? (
          <Progress
            value={progressPercent(goal)}
            aria-label={`${formatMoney(goal.savedCents)} saved of ${formatMoney(goal.targetCents)}`}
            className="h-1.5"
          />
        ) : null}
      </section>

      {canPause ? (
        <Section
          id="pause"
          title={goal.status === "PAUSED" ? "Saving is paused" : "Need a break?"}
          description={
            goal.status === "PAUSED"
              ? "No money is taken while you are paused."
              : "You can pause any month. What you have saved stays yours."
          }
        >
          <PauseResume goalId={goal.id} paused={goal.status === "PAUSED"} />
        </Section>
      ) : null}

      {canTopUp(goal.status) ? (
        <Section
          id="topup"
          title="Add money"
          description={
            goal.wallet === "VENMO"
              ? "This goal saves from Venmo, so it grows when you add money here."
              : "Add a little extra on top of your monthly amount, whenever you like."
          }
        >
          <TopUpSection goalId={goal.id} wallet={goal.wallet} clientId={process.env.NEXT_PUBLIC_PAYPAL_CLIENT_ID} />
        </Section>
      ) : null}

      {canWithdraw(goal) ? (
        <Section id="withdraw" title="Take money out" description="It is your money. Take out what you need, when you need it.">
          <WithdrawForm goalId={goal.id} attemptKey={crypto.randomUUID()} availableCents={goal.savedCents} />
        </Section>
      ) : null}

      {!pending ? (
        <Section id="withdrawals" title="Money taken out">
          <WithdrawalList withdrawals={withdrawals} />
        </Section>
      ) : null}

      <p className="text-sm text-body">
        <Link href={"/app/journey" as Route} className="inline-flex items-center gap-1.5 font-medium text-ink underline underline-offset-4">
          <RouteIcon className="size-4" aria-hidden="true" />
          See your whole journey
        </Link>{" "}
        from your first deposit to a loan.
      </p>
    </div>
  );
}
