import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Wallet } from "lucide-react";
import type { Metadata } from "next";
import { HelpBanner } from "@/features/loans/components/help-banner";
import { LoanCard } from "@/features/loans/components/loan-card";
import { getMemberHome } from "@/features/loans/server/queries";
import { requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Your loans" };

export default async function MemberHomePage() {
  const [session, loans] = await Promise.all([requireSession(), getMemberHome()]);
  const helpWaiting = loans.filter((loan) => loan.help !== null);

  return (
    <div className="flex flex-col gap-10">
      <PageHeader
        eyebrow="Your money"
        title={`Hi ${session.displayName}`}
        description="Your loans, your next payment, and any help we can offer. No fees, ever."
      />

      {helpWaiting.map((loan) => (
        <HelpBanner key={loan.loanId} loanId={loan.loanId} state={loan.help ?? "offer"} />
      ))}

      {loans.length === 0 ? (
        <EmptyState
          icon={<Wallet aria-hidden="true" />}
          title="No loans yet"
          description="When you take a Seed loan, your plan and payments show up here."
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
