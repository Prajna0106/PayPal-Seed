import { isProblem, problemMessage } from "@paypal-seed/api-client/problem";
import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatDateTime, formatMoney, formatPercentBps } from "@paypal-seed/ui/lib/format";
import { AlertTriangle, Banknote, LifeBuoy, ListChecks, Percent } from "lucide-react";
import type { ReactNode } from "react";
import { LOAN_STATUSES, STATUS_ORDER } from "../model";
import { type PortfolioSummary, portfolioSummary } from "../server/queries";
import { AgingChart } from "./aging-chart";

type Loaded = { ok: true; summary: PortfolioSummary } | { ok: false; message: string };

/** Data is loaded in try/catch and rendered outside it, so a render error still reaches the error boundary. */
async function load(): Promise<Loaded> {
  try {
    return { ok: true, summary: await portfolioSummary() };
  } catch (error) {
    return { ok: false, message: isProblem(error) ? problemMessage(error) : "We could not reach the Seed backend." };
  }
}

function Unavailable({ message }: { message: string }) {
  return (
    <p role="alert" className="flex items-center gap-2 text-sm text-danger [&_svg]:size-4">
      <AlertTriangle aria-hidden="true" />
      {message}
    </p>
  );
}

function Panel({ title, description, children }: { title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <Card className="min-w-0">
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        {description ? <CardDescription>{description}</CardDescription> : null}
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

const count = (n: number) => n.toLocaleString("en-US");

export async function SummarySections() {
  const loaded = await load();
  if (!loaded.ok) {
    return (
      <Card>
        <CardContent className="py-6">
          <Unavailable message={loaded.message} />
        </CardContent>
      </Card>
    );
  }
  const { summary } = loaded;
  const { onTimeRepayment, impact } = summary;
  const byStatus = STATUS_ORDER.flatMap((status) => {
    const row = summary.byStatus.find((s) => s.status === status);
    return row && row.loans > 0 ? [`${count(row.loans)} ${LOAN_STATUSES[status].label.toLowerCase()}`] : [];
  }).join(" · ");
  const onPlanRate = impact.firstLoanOnPlanRateBasisPoints;

  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Loans" value={count(summary.loans)} icon={<ListChecks />} hint={byStatus || "No loans yet"} />
        <Kpi
          label="Outstanding"
          value={formatMoney(summary.outstandingCents)}
          icon={<Banknote />}
          hint="Still owed on loans that are not repaid"
        />
        <Kpi
          label="On-time repayment"
          value={onTimeRepayment.rateBasisPoints === undefined ? "—" : formatPercentBps(onTimeRepayment.rateBasisPoints)}
          icon={<Percent />}
          hint={
            onTimeRepayment.rateBasisPoints === undefined
              ? "Nothing has fallen due yet"
              : `${count(onTimeRepayment.instalmentsMissed)} of ${count(onTimeRepayment.instalmentsDue)} instalments missed, even if paid later`
          }
        />
        <Kpi
          label="Open hardship cases"
          value={count(summary.openHardshipCases)}
          icon={<LifeBuoy />}
          hint="Members Seed is helping before a payment is missed"
        />
      </div>
      <div className="grid gap-6 xl:grid-cols-2">
        <Panel title="Aging" description="Loans still owed, by how late the oldest unpaid instalment is.">
          <AgingChart buckets={summary.byAgingBucket} />
        </Panel>
        <Panel
          title="Inclusion impact"
          description={`As of ${formatDateTime(summary.asOf)}. Every loan is scored without a credit score, by design, so members with no credit history are not shut out.`}
        >
          <dl className="grid gap-4 sm:grid-cols-2">
            <Figure label="Members served" value={count(impact.membersServed)} hint="Saved or borrowed" />
            <Figure
              label="Loaned with no credit score"
              value={count(impact.membersLoanedWithoutCreditScore)}
              hint={`Of ${count(impact.membersWithLoan)} members with a loan`}
            />
            <Figure
              label="Average loan"
              value={impact.loansIssued ? formatMoney(impact.averageLoanSizeCents) : "—"}
              hint={`${count(impact.loansIssued)} loan${impact.loansIssued === 1 ? "" : "s"} issued`}
            />
            <Figure
              label="First loans repaid on plan"
              value={count(impact.firstLoansRepaidOnPlan)}
              hint={
                onPlanRate === undefined
                  ? "None repaid yet"
                  : `${formatPercentBps(onPlanRate)} of ${count(impact.firstLoansRepaid)} repaid first loans`
              }
            />
          </dl>
        </Panel>
      </div>
    </>
  );
}

function Figure({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="flex flex-col gap-1 rounded-xl border border-hairline-soft p-4">
      <dt className="text-sm text-muted-ink">{label}</dt>
      <dd className="font-display text-3xl font-light tracking-tight text-ink tabular-nums">{value}</dd>
      <dd className="text-xs text-muted-ink">{hint}</dd>
    </div>
  );
}

export function SummarySkeleton() {
  return (
    <div className="flex flex-col gap-6" aria-busy="true" aria-label="Loading the portfolio figures">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} className="h-33 rounded-xl" />
        ))}
      </div>
      <div className="grid gap-6 xl:grid-cols-2">
        <Skeleton className="h-72 rounded-xl" />
        <Skeleton className="h-72 rounded-xl" />
      </div>
    </div>
  );
}
