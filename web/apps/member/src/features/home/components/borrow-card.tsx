import { Button } from "@paypal-seed/ui/components/button";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { HandCoins } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import type { LendingSection } from "../model";
import { SectionCard } from "./section-card";

const APPLY = "/app/apply" as Route;

/**
 * What Borrow means for this member today, in plain words. When it is not open yet, the reasons are the sentences
 * lending wrote (how many more months of saving, for example); nothing here is worked out in the browser.
 */
export function BorrowCard({ lending }: { lending: LendingSection | null }) {
  const eligibility = lending?.eligibility;

  if (!eligibility) {
    return (
      <SectionCard title="Borrow" icon={<HandCoins aria-hidden="true" />}>
        <p className="font-display text-2xl font-light text-ink">Saving opens the door</p>
        <p className="text-sm text-body text-pretty">Save a little each month and a fair 0% loan opens up. No fees, and you can always say no.</p>
        <Button asChild variant="outline" className="mt-auto h-11 self-start">
          <Link href={APPLY}>See how it works</Link>
        </Button>
      </SectionCard>
    );
  }

  if (eligibility.eligible) {
    return (
      <SectionCard title="Borrow" icon={<HandCoins aria-hidden="true" />}>
        <div className="flex flex-col gap-1">
          <p className="text-sm text-muted-ink">You can borrow up to</p>
          <p className="font-display text-4xl font-light tracking-tight text-ink tabular-nums">{formatMoney(eligibility.maxLoan.cents)}</p>
        </div>
        <p className="text-sm text-body text-pretty">
          0% interest and no fees, over {eligibility.minTermMonths} to {eligibility.maxTermMonths} months, with payments up to{" "}
          {formatMoney(eligibility.maxMonthlyInstalment.cents)} a month.
        </p>
        <Button asChild className="mt-auto h-11 self-start">
          <Link href={APPLY}>Start an application</Link>
        </Button>
      </SectionCard>
    );
  }

  const reasons = eligibility.reasons.slice(0, 3);
  return (
    <SectionCard title="Borrow" icon={<HandCoins aria-hidden="true" />}>
      <p className="font-display text-2xl font-light text-ink">Keep saving to unlock Borrow</p>
      {reasons.length > 0 ? (
        <ul className="flex list-disc flex-col gap-1 pl-5 text-sm text-body marker:text-muted-ink">
          {reasons.map((reason) => (
            <li key={reason} className="text-pretty">
              {reason}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-body text-pretty">Regular saving is how you unlock a fair 0% loan.</p>
      )}
      <Button asChild variant="outline" className="mt-auto h-11 self-start">
        <Link href={APPLY}>See how it works</Link>
      </Button>
    </SectionCard>
  );
}
