import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import type { Metadata } from "next";
import { FairChecksQueueLoader } from "@/features/fair-checks/components/fair-checks-grid-loader";
import { readFairCheckSearch } from "@/features/fair-checks/schema";

export const metadata: Metadata = { title: "Fair checks" };

export default async function FairChecksPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const search = readFairCheckSearch(await searchParams);
  return (
    <>
      <PageHeader
        eyebrow="Fair loan check"
        title="Fair-check decisions"
        description="Each check sizes a loan from the member's own income and saving. Open one to read the evidence, then approve, adjust within the safe limits, or decline."
      />
      <FairChecksQueueLoader key={`${search.status.join(",")}|${search.disputed}`} initialFilters={search} />
    </>
  );
}
