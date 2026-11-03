import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import type { Metadata } from "next";
import { ApplicationsGridLoader } from "@/features/applications/components/applications-grid-loader";
import { readApplicationSearch } from "@/features/applications/schema";

export const metadata: Metadata = { title: "Loan applications" };

export default async function ApplicationsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const search = readApplicationSearch(await searchParams);
  return (
    <>
      <PageHeader
        eyebrow="Microloans"
        title="Loan applications"
        description="Applications waiting for two officers, ordered by the one closest to its deadline. Open one to read the fair check, sign, reject or pay out."
      />
      <ApplicationsGridLoader key={`${search.status.join(",")}|${search.slaBreached}`} initialFilters={search} />
    </>
  );
}
