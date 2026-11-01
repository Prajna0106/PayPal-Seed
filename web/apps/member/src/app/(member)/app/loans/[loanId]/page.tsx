import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { formatBusinessDate } from "@paypal-seed/ui/lib/format";
import { ArrowLeft } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import { HelpBanner } from "@/features/loans/components/help-banner";
import { InstalmentTable } from "@/features/loans/components/instalment-table";
import { PlanSummary } from "@/features/loans/components/plan-summary";
import { PlanTimeline } from "@/features/loans/components/plan-timeline";
import { TransactionsList } from "@/features/loans/components/transactions-list";
import { getLoanDetail } from "@/features/loans/server/queries";
import { PayNowSection } from "@/features/payments/components/pay-now-section";

export const metadata: Metadata = { title: "Your plan" };

const REASON: Record<string, string> = {
  HARDSHIP_MOVE_DATE: "One payment moved to a later date",
  HARDSHIP_PAUSE: "One payment paused to the end of the plan",
  HARDSHIP_REAMORTIZE: "Smaller payments over a longer plan",
  CORRECTION: "Corrected by our team",
};

export default async function LoanPage({ params }: PageProps<"/app/loans/[loanId]">) {
  const { loanId } = await params;
  const { plan, timeline, transactions, payoffCents, help } = await getLoanDetail(loanId);
  const active = plan.loanStatus === "ACTIVE";
  const changed = REASON[plan.reason];

  return (
    <div className="flex flex-col gap-10">
      <Button asChild variant="ghost" size="sm" className="-ml-3 self-start">
        <Link href="/app">
          <ArrowLeft aria-hidden="true" />
          Your loans
        </Link>
      </Button>

      <PageHeader
        eyebrow={plan.version > 1 ? `Plan · version ${plan.version}` : "Your plan"}
        title={active ? "Your repayment plan" : "This loan is paid off"}
        description={
          changed
            ? `${changed}. Everything is shown as of ${formatBusinessDate(plan.businessDate, "long")}.`
            : `Equal payments at 0% interest. Shown as of ${formatBusinessDate(plan.businessDate, "long")}.`
        }
      />

      {help ? <HelpBanner loanId={loanId} state={help} /> : null}

      <PlanSummary plan={plan} />

      {timeline ? (
        <Section title="Timeline" description={plan.version > 1 ? "Dashed payments show your plan before the change." : undefined}>
          <PlanTimeline payload={timeline} today={plan.businessDate} />
        </Section>
      ) : null}

      <Section title="Payments">
        <InstalmentTable instalments={plan.instalments} version={plan.version} />
      </Section>

      {active ? (
        <Section title="Pay now" description="Pay your next payment early, or the whole balance. Paying early never costs anything.">
          <PayNowSection
            loanId={loanId}
            clientId={process.env.NEXT_PUBLIC_PAYPAL_CLIENT_ID}
            nextCents={plan.next?.remainingCents ?? null}
            payoffCents={payoffCents}
          />
        </Section>
      ) : null}

      <Section title="Money received">
        <TransactionsList transactions={transactions} />
      </Section>

      {active && !help ? (
        <p className="text-sm text-body">
          Worried about a payment?{" "}
          <Link href={`/app/loans/${loanId}/help` as Route} className="font-medium text-ink underline underline-offset-4">
            Ask us for help
          </Link>{" "}
          before it is due. It never counts against you.
        </p>
      ) : null}
    </div>
  );
}

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  const id = `section-${title.toLowerCase().replace(/\s+/g, "-")}`;
  return (
    <section aria-labelledby={id} className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h2 id={id} className="text-display-sm text-ink">
          {title}
        </h2>
        {description ? <p className="text-sm text-body">{description}</p> : null}
      </div>
      {children}
    </section>
  );
}
