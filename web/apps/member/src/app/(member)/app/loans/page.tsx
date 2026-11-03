import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { Wallet } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { HelpBanner } from "@/features/loans/components/help-banner";
import { LoanCard } from "@/features/loans/components/loan-card";
import { getMemberHome } from "@/features/loans/server/queries";

export const metadata: Metadata = { title: "Your loans" };

export default async function LoansPage() {
  const loans = await getMemberHome();
  const helpWaiting = loans.filter((loan) => loan.help !== null);
  const actions = (
    <>
      <Button asChild variant="outline" className="h-11">
        <Link href={"/app/applications" as Route}>Applications</Link>
      </Button>
      <Button asChild className="h-11">
        <Link href={"/app/apply" as Route}>Borrow</Link>
      </Button>
    </>
  );

  return (
    <div className="flex flex-col gap-10">
      <PageHeader
        eyebrow="Your money"
        title="Your loans"
        description="Each loan, what is left to pay and your next payment. No fees, ever."
        actions={actions}
      />

      {helpWaiting.map((loan) => (
        <HelpBanner key={loan.loanId} loanId={loan.loanId} state={loan.help ?? "offer"} />
      ))}

      {loans.length === 0 ? (
        <EmptyState
          icon={<Wallet aria-hidden="true" />}
          title="No loans yet"
          description="When you take a Seed loan, your plan and payments show up here."
          action={
            <Button asChild className="h-11">
              <Link href={"/app/apply" as Route}>See what you can borrow</Link>
            </Button>
          }
        />
      ) : (
        <section aria-label="Your loans" className="grid gap-4 md:grid-cols-2">
          {loans.map((loan) => (
            <LoanCard key={loan.loanId} loan={loan} />
          ))}
        </section>
      )}
    </div>
  );
}
