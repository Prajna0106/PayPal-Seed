import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowLeft, PiggyBank } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { ProductGoalCard } from "@/features/shop/components/product-goal-card";
import { getProductGoals } from "@/features/shop/server/queries";

export const metadata: Metadata = { title: "Your product goals" };

export default async function ShopGoalsPage() {
  const goals = await getProductGoals();

  return (
    <div className="flex flex-col gap-8">
      <Button asChild variant="ghost" size="sm" className="-ml-3 self-start">
        <Link href={"/app/shop" as Route}>
          <ArrowLeft aria-hidden="true" />
          Save for something
        </Link>
      </Button>
      <PageHeader
        eyebrow="Save for something"
        title="Your product goals"
        description="The things you are saving for, and how close you are. You decide if and when to buy."
      />
      {goals.length === 0 ? (
        <EmptyState
          icon={<PiggyBank aria-hidden="true" />}
          title="Nothing here yet"
          description="When you start saving for something, it shows up here with its progress."
          action={
            <Button asChild>
              <Link href={"/app/shop" as Route}>Find something to save for</Link>
            </Button>
          }
        />
      ) : (
        <ul className="flex flex-col gap-4">
          {goals.map((goal) => (
            <ProductGoalCard key={goal.goalId} goal={goal} />
          ))}
        </ul>
      )}
    </div>
  );
}
