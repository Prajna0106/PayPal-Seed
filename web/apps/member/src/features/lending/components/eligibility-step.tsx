import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { Button } from "@paypal-seed/ui/components/button";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowRight, PiggyBank } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { applyHref, type Eligibility, RECORD_LEVEL_WORDS } from "../model";

/** Step 1: what the member has unlocked and why, in the backend's own words. No score, ever. */
export function EligibilityStep({ eligibility }: { eligibility: Eligibility }) {
  if (!eligibility.eligible) {
    return <NotYet eligibility={eligibility} />;
  }
  return (
    <div className="flex flex-col gap-8">
      <section aria-labelledby="unlocked" className="flex flex-col gap-4">
        <h2 id="unlocked" className="text-display-sm text-ink">
          You have unlocked a Seed loan
        </h2>
        <div className="grid gap-4 sm:grid-cols-3">
          <Kpi label="You can borrow up to" value={formatMoney(eligibility.maxLoanCents)} />
          <Kpi
            label="Choose a term of"
            value={`${eligibility.minTermMonths}–${eligibility.maxTermMonths}`}
            hint="Months, in equal monthly payments."
          />
          <Kpi
            label="Each payment stays under"
            value={formatMoney(eligibility.maxMonthlyInstalmentCents)}
            hint="Per month, so it fits your month."
          />
        </div>
      </section>

      <section aria-labelledby="why" className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6">
        <h2 id="why" className="text-title-sm font-medium text-ink">
          Why this limit
        </h2>
        <p className="text-body text-pretty">{eligibility.limitReason}</p>
        <p className="text-sm text-muted-ink">Your Seed record: {RECORD_LEVEL_WORDS[eligibility.recordLevel]}</p>
      </section>

      <div className="flex flex-col gap-3">
        <Button asChild size="lg" className="h-12 self-start">
          <Link href={applyHref("amount")}>
            Choose an amount
            <ArrowRight aria-hidden="true" />
          </Link>
        </Button>
        <p className="text-sm text-muted-ink">Seed loans cost 0% and have no fees. You can stop at any step.</p>
      </div>
    </div>
  );
}

function NotYet({ eligibility }: { eligibility: Eligibility }) {
  const months = eligibility.currentSavingStreakMonths ?? 0;
  return (
    <section aria-labelledby="not-yet" className="flex flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6 sm:p-8">
      <PiggyBank aria-hidden="true" className="size-6 text-muted-ink" />
      <div className="flex flex-col gap-2">
        <h2 id="not-yet" className="text-display-sm text-ink">
          Not unlocked yet
        </h2>
        <p className="text-body text-pretty">
          A Seed loan opens up as your saving builds. You are on a {months}-month saving streak so far.
        </p>
      </div>
      {eligibility.reasons.length > 0 ? (
        <ul className="flex list-disc flex-col gap-1.5 pl-5 text-body">
          {eligibility.reasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      ) : null}
      <Button asChild size="lg" className="h-12 self-start">
        <Link href={"/app/savings" as Route}>Go to my savings</Link>
      </Button>
    </section>
  );
}
