import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { formatDateTime, formatMoney } from "@paypal-seed/ui/lib/format";
import { type LoanApplication, shortRef } from "../model";

function Figure({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs text-muted-ink">{label}</dt>
      <dd className="text-sm font-medium text-ink tabular-nums">{children}</dd>
    </div>
  );
}

/** What was asked for and how the backend sized it. Every figure is a field of the application, shown as given. */
export function ApplicationSummary({ application }: { application: LoanApplication }) {
  const reasons = application.sizingReasons ?? [];
  return (
    <Card>
      <CardHeader>
        <CardTitle>Application</CardTitle>
        <CardDescription>
          Member <span className="font-mono">{shortRef(application.memberId)}</span> · submitted{" "}
          {formatDateTime(application.createdAt)}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <dl className="grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-3">
          <Figure label="Requested">{formatMoney(application.requestedAmountCents)}</Figure>
          <Figure label="Term">{application.termMonths} months</Figure>
          {application.monthlyInstalmentCents !== undefined ? (
            <Figure label="Monthly instalment">{formatMoney(application.monthlyInstalmentCents)}</Figure>
          ) : null}
          {application.maxMonthlyInstalmentCents !== undefined ? (
            <Figure label="Instalment ceiling">{formatMoney(application.maxMonthlyInstalmentCents)}</Figure>
          ) : null}
          {application.unlockedTier ? <Figure label="Tier unlocked">{application.unlockedTier}</Figure> : null}
          {application.payoutWallet ? (
            <Figure label="Payout to">{application.payoutWallet === "VENMO" ? "Venmo" : "PayPal"}</Figure>
          ) : null}
          {application.preferredDueDay ? <Figure label="Preferred due day">{application.preferredDueDay}</Figure> : null}
          <Figure label="Fair check consent">{application.fairCheckConsented ? "Given" : "Not given"}</Figure>
        </dl>

        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium text-ink">Purpose</h3>
          <p className="text-sm text-body text-pretty">{application.purpose}</p>
        </div>

        {reasons.length > 0 ? (
          <div className="flex flex-col gap-2">
            <h3 className="text-sm font-medium text-ink">How it was sized</h3>
            <ul className="flex list-disc flex-col gap-1 pl-5 text-sm text-body">
              {reasons.map((reason) => (
                <li key={reason}>{reason}</li>
              ))}
            </ul>
          </div>
        ) : null}

        {application.rejectionReason ? (
          <div className="flex flex-col gap-1 rounded-xl bg-danger-soft p-3 text-sm text-danger">
            <h3 className="font-medium">Rejection reason</h3>
            <p>{application.rejectionReason}</p>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
