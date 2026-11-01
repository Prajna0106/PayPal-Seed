import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Progress } from "@paypal-seed/ui/components/progress";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowRight } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import type { LoanCardModel } from "../server/queries";

/** One loan on the member home: what is left, the next payment, progress, and how it is paid. */
export function LoanCard({ loan }: { loan: LoanCardModel }) {
  const { plan } = loan;
  const total = plan?.instalments.length ?? 0;
  const paid = plan?.paidCount ?? 0;
  const href = `/app/loans/${loan.loanId}` as Route;

  return (
    <article className="group relative flex flex-col gap-6 rounded-2xl border border-hairline bg-surface p-6 transition-shadow hover:shadow-[0_4px_16px_rgba(0,0,0,0.04)]">
      <header className="flex items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <p className="text-eyebrow text-muted-ink">0% loan · {formatMoney(loan.principalCents)}</p>
          <h2 className="text-display-sm text-ink">
            <Link href={href} className="after:absolute after:inset-0 after:rounded-2xl focus-visible:outline-none">
              {formatMoney(loan.outstandingCents)} left to pay
            </Link>
          </h2>
        </div>
        {loan.status === "ACTIVE" ? (
          <StatusPill tone={loan.autopay ? "success" : "neutral"}>{loan.autopay ? "Autopay on" : "Pay as you go"}</StatusPill>
        ) : (
          <StatusPill tone="success">{loan.status === "REPAID" ? "Paid off" : loan.status.toLowerCase()}</StatusPill>
        )}
      </header>

      {plan?.next ? (
        <dl className="grid grid-cols-2 gap-4 text-sm">
          <div className="flex flex-col gap-1">
            <dt className="text-muted-ink">Next payment</dt>
            <dd className="text-base font-medium text-ink tabular-nums">{formatMoney(plan.next.remainingCents)}</dd>
          </div>
          <div className="flex flex-col gap-1">
            <dt className="text-muted-ink">Due</dt>
            <dd className="text-base font-medium text-ink">{formatBusinessDate(plan.next.dueDate, "long")}</dd>
          </div>
        </dl>
      ) : null}

      {total > 0 ? (
        <div className="flex flex-col gap-2">
          <Progress value={(paid / total) * 100} aria-label={`${paid} of ${total} payments made`} className="h-1.5" />
          <p className="text-xs text-muted-ink">
            {paid} of {total} payments made
          </p>
        </div>
      ) : null}

      <span className="inline-flex items-center gap-1 text-sm font-medium text-ink" aria-hidden="true">
        See your plan <ArrowRight className="size-4 transition-transform group-hover:translate-x-0.5" />
      </span>
    </article>
  );
}
