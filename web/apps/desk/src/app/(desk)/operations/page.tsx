import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { CalendarClock, Link2, Lock, Radar, Sunset } from "lucide-react";
import type { Metadata } from "next";
import { Suspense } from "react";
import { AdvanceClock, RunOperation } from "@/features/operations/components/operation-card";
import { CobSection, PanelSkeleton } from "@/features/overview/components/sections";
import { hasRole, requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Operations" };

export default async function OperationsPage() {
  const session = await requireSession();
  if (!hasRole(session, "ADMIN")) {
    return (
      <>
        <PageHeader eyebrow="Platform" title="Operations" />
        <EmptyState
          icon={<Lock />}
          title="Admins only"
          description="Operations run internal jobs with the service token. Switch persona to Prajna Jeet Ojha (bottom left) to use them."
        />
      </>
    );
  }
  return (
    <>
      <PageHeader
        eyebrow="Platform"
        title="Operations"
        description="The jobs Render Workflows runs on a schedule, available on demand for the demo. Each one is idempotent: running it twice is safe."
      />
      <div className="grid gap-6 md:grid-cols-2">
        <RunOperation
          kind="scan"
          title="Hardship scan"
          description="Scores every loan with a payment due in the next 10 days or in grace, opens cases at Likely to miss, then fires due timers."
          icon={<Radar />}
        />
        <RunOperation
          kind="cob"
          title="Close of business"
          description="Closes each business date in order: ages loans, fires reminders and grace timers, records late buckets."
          icon={<Sunset />}
        />
        <AdvanceClock icon={<CalendarClock />} />
        <RunOperation
          kind="verify"
          title="Audit chain"
          description="Recomputes the SHA-256 hash chain over every proposal, verdict and decision. A break means a row was altered."
          icon={<Link2 />}
        />
      </div>
      <Suspense fallback={<PanelSkeleton height="h-48" />}>
        <CobSection />
      </Suspense>
    </>
  );
}
