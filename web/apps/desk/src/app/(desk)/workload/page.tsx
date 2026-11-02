import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import type { Metadata } from "next";
import { Suspense } from "react";
import { WorkloadSkeleton, WorkloadView } from "@/features/workload/components/workload-view";

export const metadata: Metadata = { title: "Workload" };

export default function WorkloadPage() {
  return (
    <>
      <PageHeader
        eyebrow="Portfolio"
        title="Officer workload"
        description="Approvals waiting for an officer or a checker, grouped by how close they are to their deadline."
      />
      <Suspense fallback={<WorkloadSkeleton />}>
        <WorkloadView />
      </Suspense>
    </>
  );
}
