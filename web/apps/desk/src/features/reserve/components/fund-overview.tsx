import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatBusinessDate, formatMoney, formatPercentBps } from "@paypal-seed/ui/lib/format";
import { HandCoins, Landmark, PiggyBank, ShieldCheck, Users, Wallet } from "lucide-react";
import { reserveErrorMessage } from "../model";
import { reserveSummary } from "../server/queries";
import { SectionError } from "./section-error";

export function FundOverviewSkeleton() {
  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3" aria-busy="true" aria-label="Loading the fund">
      {Array.from({ length: 6 }, (_, i) => (
        <Skeleton key={i} className="h-32 rounded-xl" />
      ))}
    </div>
  );
}

/** The community fund as the public sees it: balance, gifts, what it paid to loans, and how much of the open lending it could cover. */
export async function FundOverview() {
  let summary: Awaited<ReturnType<typeof reserveSummary>>;
  try {
    summary = await reserveSummary();
  } catch (error) {
    return <SectionError title="The fund figures could not be loaded" message={reserveErrorMessage(error)} />;
  }
  return (
    <section aria-label="Fund overview" className="flex flex-col gap-3">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <Kpi label="Fund balance" value={formatMoney(summary.balanceCents)} hint="Available to pay members' loans" icon={<Landmark aria-hidden="true" />} />
        <Kpi
          label="Total donated"
          value={formatMoney(summary.totalDonatedCents)}
          hint={`${summary.donationCount.toLocaleString("en-US")} gift${summary.donationCount === 1 ? "" : "s"}, donors anonymous by default`}
          icon={<PiggyBank aria-hidden="true" />}
        />
        <Kpi
          label="Paid to loans"
          value={formatMoney(summary.totalPaidOutCents)}
          hint="Paid to loans on behalf of members, never to a person"
          icon={<HandCoins aria-hidden="true" />}
        />
        <Kpi
          label="Members covered"
          value={summary.membersCovered.toLocaleString("en-US")}
          hint="Members whose loan the fund has helped pay"
          icon={<Users aria-hidden="true" />}
        />
        <Kpi
          label="Open exposure"
          value={formatMoney(summary.openExposureCents)}
          hint="Outstanding balance of all open loans"
          icon={<Wallet aria-hidden="true" />}
        />
        <Kpi
          label="Coverage ratio"
          value={summary.coverageRatioBps === null ? "—" : formatPercentBps(summary.coverageRatioBps)}
          hint={summary.coverageRatioBps === null ? "No open loans to cover" : "Fund balance over open exposure; 100% would repay every open loan"}
          icon={<ShieldCheck aria-hidden="true" />}
        />
      </div>
      <p className="text-xs text-muted-ink">As of {formatBusinessDate(summary.asOf, "long")}. The figures come from the fund&apos;s own record, kept in step with the ledger.</p>
    </section>
  );
}
