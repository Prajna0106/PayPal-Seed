import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { Wallet } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { autopayLabel, autopayTone } from "../copy";
import type { ActiveLoan } from "../model";
import { SectionCard } from "./section-card";

/** The active loan at a glance: what is left, the next payment and its date, autopay, and a way to pay. */
export function LoanSnapshot({ loan }: { loan: ActiveLoan }) {
  const href = `/app/loans/${loan.loanId}` as Route;
  return (
    <SectionCard title="Your loan" icon={<Wallet aria-hidden="true" />}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <p className="font-display text-4xl font-light tracking-tight text-ink tabular-nums">{formatMoney(loan.outstanding.cents)}</p>
          <p className="text-sm text-muted-ink">left to pay, 0% interest</p>
        </div>
        <StatusPill tone={autopayTone(loan.autopay?.status)}>{autopayLabel(loan.autopay?.status)}</StatusPill>
      </div>

      {loan.nextPayment ? (
        <dl className="grid grid-cols-2 gap-4 text-sm">
          <div className="flex flex-col gap-1">
            <dt className="text-muted-ink">Next payment</dt>
            <dd className="text-base font-medium text-ink tabular-nums">{formatMoney(loan.nextPayment.remaining.cents)}</dd>
          </div>
          <div className="flex flex-col gap-1">
            <dt className="text-muted-ink">Due</dt>
            <dd className="text-base font-medium text-ink">{formatBusinessDate(loan.nextPayment.dueDate, "long")}</dd>
          </div>
        </dl>
      ) : (
        <p className="text-sm text-body">Nothing is due right now.</p>
      )}

      <div className="mt-auto flex flex-wrap gap-2">
        <Button asChild className="h-11">
          <Link href={href}>Pay now</Link>
        </Button>
        <Button asChild variant="outline" className="h-11">
          <Link href={href}>See your plan</Link>
        </Button>
      </div>
    </SectionCard>
  );
}
