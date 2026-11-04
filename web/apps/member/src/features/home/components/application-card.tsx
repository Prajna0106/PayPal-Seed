import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { formatDateTime, formatMoney } from "@paypal-seed/ui/lib/format";
import { FileText } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { applicationStatus, PAYOUT_ISSUE, PAYOUT_RETURNED } from "../copy";
import type { OpenApplication } from "../model";
import { SectionCard } from "./section-card";

/** The loan application in progress: where it stands in plain words, and a link to follow it. */
export function ApplicationCard({ application }: { application: OpenApplication }) {
  const status = applicationStatus(application.status);
  return (
    <SectionCard title="Your application" icon={<FileText aria-hidden="true" />}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <p className="font-display text-3xl font-light tracking-tight text-ink tabular-nums">{formatMoney(application.requested.cents)}</p>
          <p className="text-sm text-muted-ink">over {application.termMonths} months</p>
        </div>
        <StatusPill tone={status.tone}>{status.label}</StatusPill>
      </div>
      <p className="text-sm text-body text-pretty">{status.detail}</p>
      {application.coolingOffExpiresAt ? (
        <p className="text-xs text-muted-ink">
          Cooling-off, when you can cancel without a reason: until {formatDateTime(application.coolingOffExpiresAt)}.
        </p>
      ) : null}
      {application.payoutIssue ? (
        <p className="rounded-xl bg-warning-soft px-3 py-2 text-sm text-warning text-pretty">{application.payoutIssue === "RETURNED_AFTER_DISBURSEMENT" ? PAYOUT_RETURNED : PAYOUT_ISSUE}</p>
      ) : null}
      <Button asChild variant="outline" className="mt-auto h-11 self-start">
        <Link href={`/app/applications/${application.applicationId}` as Route}>Follow your application</Link>
      </Button>
    </SectionCard>
  );
}
