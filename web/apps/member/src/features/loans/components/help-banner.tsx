import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Button } from "@paypal-seed/ui/components/button";
import type { Route } from "next";
import Link from "next/link";

/**
 * A gentle invitation when the safety net has something for this loan. No urgency, no risk words: the member
 * decides whether to look.
 */
export function HelpBanner({ loanId, state }: { loanId: string; state: "offer" | "reviewing" }) {
  const offer = state === "offer";
  return (
    <aside
      aria-labelledby={`help-${loanId}`}
      className="relative isolate flex flex-col gap-4 overflow-hidden rounded-3xl border border-hairline bg-canvas-soft p-6 sm:flex-row sm:items-center sm:justify-between sm:p-8"
    >
      <GradientOrb tone="mint" blend="lavender" className="-top-20 -right-10 -z-10 size-64 opacity-60" />
      <div className="flex max-w-xl flex-col gap-2">
        <h2 id={`help-${loanId}`} className="text-display-sm text-ink">
          {offer ? "Need help with a payment?" : "We have your choice"}
        </h2>
        <p className="text-body text-pretty">
          {offer
            ? "If this month looks tight, we have a few ways to make it easier. No fees, and you can say no."
            : "Our team is reviewing the option you chose. Your plan stays as it is until then."}
        </p>
      </div>
      <Button asChild variant={offer ? "default" : "outline"} className="self-start sm:self-auto">
        <Link href={`/app/loans/${loanId}/help` as Route}>{offer ? "See your options" : "See your request"}</Link>
      </Button>
    </aside>
  );
}
