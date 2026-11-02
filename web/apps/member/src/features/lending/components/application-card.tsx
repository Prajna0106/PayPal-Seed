import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { formatDateTime, formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowRight } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import type { ApplicationModel } from "../server/queries";
import { APPLICATION_STATUS } from "../status";

/** One application in the list: what was asked, where it is, and a link to the detail. */
export function ApplicationCard({ application }: { application: ApplicationModel }) {
  const status = APPLICATION_STATUS[application.status];
  const href = `/app/applications/${application.id}` as Route;
  return (
    <article className="group relative flex flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6 transition-shadow hover:shadow-soft">
      <header className="flex items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <p className="text-eyebrow text-muted-ink">{formatDateTime(application.createdAt, { dateStyle: "medium" })}</p>
          <h2 className="text-display-sm text-ink">
            <Link href={href} className="after:absolute after:inset-0 after:rounded-2xl focus-visible:outline-none focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50">
              {formatMoney(application.amountCents)} over {application.termMonths} months
            </Link>
          </h2>
        </div>
        <StatusPill tone={status.tone}>{status.label}</StatusPill>
      </header>
      <p className="text-sm text-body">
        {application.purpose}
        {application.monthlyCents ? ` · ${formatMoney(application.monthlyCents)} a month` : ""}
      </p>
      <span className="inline-flex items-center gap-1 text-sm font-medium text-ink" aria-hidden="true">
        See details <ArrowRight className="size-4 transition-transform group-hover:translate-x-0.5 motion-reduce:transition-none" />
      </span>
    </article>
  );
}
