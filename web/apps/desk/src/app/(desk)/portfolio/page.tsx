import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import type { Metadata } from "next";
import { Suspense } from "react";
import { LoanFilters } from "@/features/portfolio/components/loan-filters";
import { LoanGridLoader } from "@/features/portfolio/components/loan-grid-loader";
import { SummarySections, SummarySkeleton } from "@/features/portfolio/components/summary-sections";
import { loanQuery, readLoanSearch } from "@/features/portfolio/filters";

export const metadata: Metadata = { title: "Portfolio" };

export default async function PortfolioPage({ searchParams }: PageProps<"/portfolio">) {
  const search = readLoanSearch(await searchParams);
  const query = loanQuery(search).toString();
  return (
    <>
      <PageHeader
        eyebrow="Portfolio"
        title="Loan portfolio"
        description="Where the loan book stands, and who it reaches. Open a loan to see its schedule and payments."
      />
      <Suspense fallback={<SummarySkeleton />}>
        <SummarySections />
      </Suspense>
      <section aria-labelledby="loans-heading" className="flex flex-col gap-4">
        <h2 id="loans-heading" className="font-display text-2xl font-light text-ink">
          Loans
        </h2>
        <Suspense>
          <LoanFilters search={search} />
        </Suspense>
        <LoanGridLoader key={query} query={query} />
      </section>
    </>
  );
}
