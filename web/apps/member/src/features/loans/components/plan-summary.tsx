import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import type { Plan } from "../model";

/** The four facts a member looks for first. */
export function PlanSummary({ plan }: { plan: Plan }) {
  const total = plan.instalments.length;
  return (
    <section aria-label="Plan summary" className="grid grid-cols-2 gap-4 lg:grid-cols-4">
      <Kpi label="Borrowed" value={formatMoney(plan.principalCents)} hint="0% interest, no fees" />
      <Kpi label="Left to pay" value={formatMoney(plan.outstandingCents)} hint={`${plan.paidCount} of ${total} payments made`} />
      <Kpi
        label="Next payment"
        value={plan.next ? formatMoney(plan.next.remainingCents) : "None"}
        hint={plan.next ? `Due ${formatBusinessDate(plan.next.dueDate, "long")}` : "You're all paid up"}
      />
      <Kpi
        label="How you pay"
        value={plan.autopay ? "Autopay" : "As you go"}
        hint={plan.autopay ? "Collected by PayPal on each due date" : "Pay with PayPal when you're ready"}
      />
    </section>
  );
}
