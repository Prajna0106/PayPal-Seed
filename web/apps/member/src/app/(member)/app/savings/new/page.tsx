import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowLeft } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { GoalForm } from "@/features/savings/components/goal-form";
import { getNewGoalDefaults } from "@/features/savings/server/queries";

export const metadata: Metadata = { title: "Start a savings goal" };

export default async function NewGoalPage({ searchParams }: PageProps<"/app/savings/new">) {
  const [{ name }, defaults] = await Promise.all([searchParams, getNewGoalDefaults()]);

  return (
    <div className="flex flex-col gap-10">
      <Button asChild variant="ghost" size="sm" className="-ml-3 min-h-11 self-start">
        <Link href={"/app/savings" as Route}>
          <ArrowLeft aria-hidden="true" />
          Your goals
        </Link>
      </Button>

      <PageHeader
        eyebrow="Your savings"
        title="Start a savings goal"
        description="Save from $5 a month. It stays yours to take out, and you can pause any time."
      />

      <div className="max-w-xl">
        <GoalForm
          attemptKey={crypto.randomUUID()}
          initialName={typeof name === "string" ? name.slice(0, 60) : ""}
          initialWallet={defaults.wallet}
          language={defaults.language}
        />
      </div>
    </div>
  );
}
