import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { z } from "zod";
import { ApprovalWatcher } from "@/features/savings/components/approval-watcher";
import { findGoal } from "@/features/savings/server/queries";

export const metadata: Metadata = { title: "Finishing at PayPal" };

/**
 * Where PayPal sends the member after they agree (`SEED_SAVINGS_RETURN_URL`, which adds `?goalId=`). The backend
 * activates the goal when PayPal's message arrives, so this page waits for it instead of assuming.
 */
export default async function SavingsReturnPage({ searchParams }: PageProps<"/app/savings/return">) {
  const { goalId } = await searchParams;
  const id = z.uuid().safeParse(Array.isArray(goalId) ? goalId[0] : goalId);
  const goal = id.success ? await findGoal(id.data) : null;

  return (
    <div className="flex max-w-2xl flex-col gap-8">
      <PageHeader
        eyebrow="Your savings"
        title={goal ? "Finishing at PayPal" : "We could not find that goal"}
        description={
          goal
            ? "Thank you for agreeing. We are checking with PayPal that everything is in place."
            : "Your goals are on your savings page, with anything still waiting for you."
        }
      />
      {goal ? (
        <ApprovalWatcher goalId={goal.id} goalName={goal.name} status={goal.status} />
      ) : (
        <Button asChild className="self-start">
          <Link href={"/app/savings" as Route}>Go to your goals</Link>
        </Button>
      )}
    </div>
  );
}
