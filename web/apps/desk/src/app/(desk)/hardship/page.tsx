import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import type { Metadata } from "next";
import { Suspense } from "react";
import { QueueFilters } from "@/features/hardship/components/queue-filters";
import { QueueGridLoader } from "@/features/hardship/components/queue-grid-loader";
import { queueQuery, readQueueSearch } from "@/features/hardship/filters";

export const metadata: Metadata = { title: "Hardship queue" };

export default async function HardshipQueuePage({ searchParams }: PageProps<"/hardship">) {
  const search = readQueueSearch(await searchParams);
  const query = queueQuery(search).toString();
  return (
    <>
      <PageHeader
        eyebrow="Safety net"
        title="Hardship queue"
        description="Members the daily scan flagged or who asked for help. Open a case to see the evidence, the options and the member's choice."
        actions={
          <Suspense>
            <QueueFilters search={search} />
          </Suspense>
        }
      />
      <QueueGridLoader key={query} query={query} />
    </>
  );
}
