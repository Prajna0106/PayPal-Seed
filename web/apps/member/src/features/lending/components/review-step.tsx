import { formatMoney } from "@paypal-seed/ui/lib/format";
import { randomUUID } from "node:crypto";
import { Suspense } from "react";
import type { ReviewContext } from "../server/queries";
import type { WizardParams } from "../model";
import { SchedulePreview, SchedulePreviewSkeleton } from "./schedule-preview";
import { SubmitForm } from "./submit-form";

/**
 * Step 5. What the member is about to send, taken from the fair check on the backend, with the payment calendar the
 * backend would create today (a preview: the real schedule is created when the money is sent).
 */
export function ReviewStep({ review, params }: { review: ReviewContext; params: WizardParams }) {
  const { card, walletPreference } = review;
  const attemptKey = randomUUID();
  const venmo = walletPreference === "VENMO";
  const rows: [string, string][] = [
    ["You borrow", formatMoney(card.suggestedLoanCents)],
    ["Number of monthly payments", String(card.suggestedTermMonths)],
    ["Each monthly payment", formatMoney(card.suggestedMonthlyInstalmentCents)],
    ["Interest and fees", "None"],
    ["What it is for", card.purpose],
    ...(params.dueDay ? ([["Payment day of the month", `Day ${params.dueDay}`]] as [string, string][]) : []),
    ["Money is sent to", venmo ? "Your Venmo" : "Your PayPal account"],
  ];

  return (
    <div className="flex flex-col gap-8">
      <section aria-labelledby="summary" className="flex flex-col gap-4 rounded-2xl border border-hairline bg-surface p-6">
        <h2 id="summary" className="text-display-sm text-ink">
          Your loan
        </h2>
        <dl className="flex flex-col divide-y divide-hairline">
          {rows.map(([label, value]) => (
            <div key={label} className="flex items-baseline justify-between gap-6 py-3 first:pt-0 last:pb-0">
              <dt className="text-body">{label}</dt>
              <dd className="text-right font-medium text-ink tabular-nums">{value}</dd>
            </div>
          ))}
        </dl>
        <p className="text-sm text-muted-ink text-pretty">You repay exactly what you borrow.</p>
      </section>

      <section aria-label="Payment calendar" className="rounded-2xl border border-hairline bg-surface p-6">
        <Suspense fallback={<SchedulePreviewSkeleton />}>
          <SchedulePreview amountCents={card.suggestedLoanCents} termMonths={card.suggestedTermMonths} dueDay={params.dueDay ?? null} />
        </Suspense>
      </section>

      <section aria-labelledby="changed-mind" className="flex flex-col gap-2">
        <h2 id="changed-mind" className="text-title-sm font-medium text-ink">
          You can change your mind
        </h2>
        <p className="text-body text-pretty">
          Once the money arrives you have 48 hours to return it, at no cost. After that you can still pay early whenever you like, also at
          no cost.
        </p>
      </section>

      <SubmitForm assessmentId={card.assessmentId} attemptKey={attemptKey} dueDay={params.dueDay ?? null} />
    </div>
  );
}
