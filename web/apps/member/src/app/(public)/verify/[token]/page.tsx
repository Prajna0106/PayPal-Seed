import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { formatBusinessDate, formatDateTime } from "@paypal-seed/ui/lib/format";
import { BadgeCheck, CircleAlert } from "lucide-react";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import { LEVEL_NAME } from "@/features/record/model";
import { verifyShare } from "@/features/record/server/verify";

// The token is a secret in the URL: keep the page out of search engines and out of Referer headers.
export const metadata: Metadata = {
  title: "Verify a Seed record",
  robots: { index: false, follow: false, nocache: true },
  referrer: "no-referrer",
};

export default async function VerifyPage({ params }: PageProps<"/verify/[token]">) {
  const { token } = await params;
  const record = await verifyShare(token);

  if (!record) {
    return (
      <Card>
        <div className="flex flex-col items-start gap-3">
          <CircleAlert className="size-8 text-muted-ink" aria-hidden="true" />
          <h1 className="text-display-md text-ink">This link is not valid or has expired</h1>
          <p className="text-body">Please ask the person who shared it to send you a new one.</p>
        </div>
      </Card>
    );
  }

  const rows: Array<[string, ReactNode]> = [];
  if (record.since) {
    rows.push(["Record began", formatBusinessDate(record.since, "month")]);
  }
  if (record.loansRepaidOnPlan !== undefined) {
    rows.push(["Loans repaid on plan", record.loansRepaidOnPlan]);
  }
  if (record.onTimeBand) {
    rows.push(["Paying on time", `${record.onTimeBand} of months and payments`]);
  }
  rows.push(["Issued", formatDateTime(record.issuedAt)], ["Link expires", formatDateTime(record.expiresAt)]);

  return (
    <Card>
      <div className="flex flex-col gap-6">
        <div className="flex flex-col gap-3">
          {record.valid ? (
            <StatusPill tone="success">
              <BadgeCheck className="size-3.5" aria-hidden="true" />
              Verified by Seed
            </StatusPill>
          ) : (
            <StatusPill tone="warning">No longer current</StatusPill>
          )}
          <p className="text-eyebrow text-muted-ink">Seed record level</p>
          <h1 className="text-display-lg text-ink">{LEVEL_NAME[record.level]}</h1>
          {record.valid ? null : (
            <p className="text-body">
              This record has changed since the link was made, so please do not rely on it. Ask the person for a new link.
            </p>
          )}
        </div>

        <dl className="divide-y divide-hairline rounded-xl border border-hairline">
          {rows.map(([label, value]) => (
            <div key={label} className="flex items-baseline justify-between gap-4 px-4 py-3">
              <dt className="text-sm text-body">{label}</dt>
              <dd className="text-right text-sm font-medium text-ink">{value}</dd>
            </div>
          ))}
        </dl>

        <p className="text-sm text-muted-ink">
          Seed records are levels built from saving and repaying, not credit scores. This page shows only what the
          member chose to share. It has no name, email or loan amounts.
        </p>
      </div>
    </Card>
  );
}

function Card({ children }: { children: ReactNode }) {
  return <div className="mx-auto w-full max-w-lg rounded-2xl border border-hairline bg-surface p-6 shadow-soft sm:p-8">{children}</div>;
}
