import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { formatBusinessDate, formatMoney, formatPercentBps } from "@paypal-seed/ui/lib/format";
import type { ReserveSummary } from "../server/queries";

/** Everything the fund holds and pays, shown as the backend gave it. Donors are never identified. */
export function FundDashboard({ summary }: { summary: ReserveSummary }) {
  return (
    <div className="flex flex-col gap-6">
      <section aria-label="Fund figures" className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Kpi label="In the fund now" value={formatMoney(summary.balanceCents)} hint="Ready to cover a hard month" />
        <Kpi
          label="Given so far"
          value={formatMoney(summary.totalDonatedCents)}
          hint={`${summary.donationCount} ${summary.donationCount === 1 ? "gift" : "gifts"}`}
        />
        <Kpi label="Paid to loans" value={formatMoney(summary.totalPaidOutCents)} hint="Always to a loan, never to a person" />
        <Kpi label="Members helped" value={summary.membersCovered} hint="Had a hard month covered" />
      </section>

      <section aria-label="Where the money stands" className="flex flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6">
        <Bar label="Given" valueText={formatMoney(summary.totalDonatedCents)} value={summary.totalDonatedCents} max={summary.totalDonatedCents} />
        <Bar label="Paid to loans" valueText={formatMoney(summary.totalPaidOutCents)} value={summary.totalPaidOutCents} max={summary.totalDonatedCents} />
        <Bar label="In the fund now" valueText={formatMoney(summary.balanceCents)} value={summary.balanceCents} max={summary.totalDonatedCents} />
        <p className="text-sm text-body">
          {summary.coverageRatioBps === null
            ? "There are no open loans right now, so there is nothing to cover."
            : `The fund holds enough to repay ${formatPercentBps(summary.coverageRatioBps)} of what members still owe (${formatMoney(summary.openExposureCents)} across open loans).`}
        </p>
        <p className="text-xs text-muted-ink">As of {formatBusinessDate(summary.asOf, "long")}.</p>
      </section>

      {summary.supporters.length > 0 ? (
        <section aria-label="Recent supporters" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium text-ink">Thank you to our recent supporters</h3>
          <p className="text-sm text-body">{summary.supporters.join(", ")}</p>
        </section>
      ) : null}
    </div>
  );
}

function Bar({ label, valueText, value, max }: { label: string; valueText: string; value: number; max: number }) {
  // Only the bar's length is worked out here, against the largest figure; the numbers shown come from the backend.
  const percent = max <= 0 ? 0 : Math.min(100, Math.max(0, Math.round((value / max) * 100)));
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="text-body">{label}</span>
        <span className="font-medium text-ink tabular-nums">{valueText}</span>
      </div>
      <div aria-hidden="true" className="h-2.5 overflow-hidden rounded-full bg-surface-strong">
        <div className="h-full rounded-full bg-ink" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}
