import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import type { Metadata, Route } from "next";
import Link from "next/link";

export const metadata: Metadata = { title: "No problem" };

/** Where PayPal sends a member who backs out (`SEED_SAVINGS_CANCEL_URL`). Calm, and nothing was taken. */
export default function SavingsCancelledPage() {
  return (
    <div className="flex max-w-2xl flex-col gap-8">
      <PageHeader
        eyebrow="Your savings"
        title="No problem, nothing was taken"
        description="You left PayPal without agreeing, so no money moved and your goal has not started."
      />
      <div className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6">
        <p className="text-sm text-body">
          Your goal waits for you on your savings page for a day. You can finish approving it there, or start again with a different
          amount. If you do nothing, it closes by itself.
        </p>
        <div className="flex flex-wrap gap-2">
          <Button asChild>
            <Link href={"/app/savings" as Route}>Back to your goals</Link>
          </Button>
          <Button asChild variant="outline">
            <Link href={"/app/savings/new" as Route}>Start again</Link>
          </Button>
        </div>
      </div>
    </div>
  );
}
